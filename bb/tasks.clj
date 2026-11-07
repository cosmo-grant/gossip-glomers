(ns tasks
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as process]))

(defn read-challenges []
  (edn/read-string (slurp "challenges.edn")))

(def maelstrom-path "./maelstrom/maelstrom")

(def default-native-image-path
  "")

(def graal-jar-path "target/gossip-glomers-graal.jar")

(def cli-spec
  {:mode {:default "maelstrom"
          :validate #{"maelstrom" "direct"}}
   :runner {:default "bb"
            :validate #{"bb" "lein" "graal"}}
   :quiet {:coerce :boolean}})

(def usage
  "Usage: bb eval <name> [--mode maelstrom|direct] [--runner bb|lein|graal] [--quiet]")

(defn print-err! [message]
  (binding [*out* *err*]
    (println message)))

(defn exit! [message]
  (print-err! message)
  (System/exit 1))

(defn native-image-path []
  (->> [(some-> (System/getenv "GRAALVM_HOME")
                (fs/path "bin" "native-image"))
        (fs/which "native-image")
        default-native-image-path]
       (remove nil?)
       (filter fs/executable?)
       first))

(defn missing-tools [{:keys [mode runner]}]
  (cond-> []
    (and (= "bb" runner)
         (not (fs/which "bb")))
    (conj "bb (not on PATH)")

    (and (#{"lein" "graal"} runner)
         (not (fs/which "lein")))
    (conj "lein (not on PATH)")

    (and (or (#{"lein" "graal"} runner)
             (= "maelstrom" mode))
         (not (fs/which "java")))
    (conj "java (not on PATH)")

    (and (= "graal" runner)
         (not (native-image-path)))
    (conj "native-image (install GraalVM, set GRAALVM_HOME)")

    (and (= "maelstrom" mode)
         (not (fs/exists? maelstrom-path)))
    (conj (str maelstrom-path " (see https://github.com/jepsen-io/maelstrom/releases)"))

    (and (= "maelstrom" mode)
         (not (fs/which "gnuplot")))
    (conj "gnuplot (brew install gnuplot)")

    (and (= "maelstrom" mode)
         (not (fs/which "dot")))
    (conj "graphviz (brew install graphviz)")))

(defn check-tools! [options]
  (let [missing (missing-tools options)]
    (when (seq missing)
      (exit! (->> missing
                  (map (fn [tool] (str "  - " tool)))
                  (cons "Missing tools:")
                  (string/join "\n"))))))

(defn native-binary-path [challenge-ns]
  (str (fs/path "target" "graal" "bin" (str challenge-ns))))

(defn node-command [runner challenge-ns]
  (case runner
    "bb" ["bb" "-m" (str challenge-ns)]
    "lein" ["lein" "with-profile" "-dev" "run" "-m" (str challenge-ns)]
    "graal" [(native-binary-path challenge-ns)]))

(defn write-wrapper! [{:keys [challenge-name runner challenge-ns]}]
  (let [path (fs/path "target" "bin" (str challenge-name "-" runner ".sh"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path)
          (str "#!/bin/sh\n"
               "cd \"" (fs/canonicalize ".") "\"\n"
               "exec " (string/join " " (node-command runner challenge-ns)) "\n"))
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    (str path)))

(defn shell!
  ([command] (shell! {} command))
  ([process-opts command]
   (print-err! (str "$ " (string/join " " command)))
   (let [{:keys [exit]} (apply process/shell (assoc process-opts :continue true) command)]
     exit)))

(defn stale? [target sources]
  (or (not (fs/exists? target))
      (seq (fs/modified-since target sources))))

(defn shell-or-exit! [command]
  (let [exit (shell! command)]
    (when-not (zero? exit)
      (exit! (str "# failed (exit " exit "): " (string/join " " command))))))

(defn build-native! [{:keys [challenge-ns]}]
  (let [binary (native-binary-path challenge-ns)]
    (when (stale? graal-jar-path ["src" "project.clj"])
      (shell-or-exit! ["lein" "with-profile" "-dev,+graal" "uberjar"]))
    (when (stale? binary [graal-jar-path])
      (fs/create-dirs (fs/parent binary))
      (shell-or-exit! [(str (native-image-path))
                       "-cp" graal-jar-path
                       "--features=clj_easy.graal_build_time.InitClojureClasses"
                       ;; cheshire stores a JsonFactory in a var
                       "--initialize-at-build-time=com.fasterxml.jackson"
                       "--no-fallback"
                       "-H:+ReportExceptionStackTraces"
                       "-o" binary
                       (munge (str challenge-ns))]))))

(defn run-direct! [{:keys [runner challenge-ns]}]
  (System/exit (shell! (node-command runner challenge-ns))))

(defn run-maelstrom! [{:keys [runner challenge challenge-ns quiet] :as options}]
  (let [wrapper (write-wrapper! options)]
    (print-err! (str "# " wrapper " runs: " (string/join " " (node-command runner challenge-ns))))
    (let [exit (shell! (if quiet {:out :discard :err :discard} {})
                       (concat [(:challenge/maelstrom-path challenge maelstrom-path) "test"
                                "-w" (:challenge/workload challenge)
                                "--bin" wrapper]
                               (:challenge/maelstrom-args challenge)))]
      (when quiet
        (print-err! (str "# exit " exit "; see store/latest/results.edn and store/latest/jepsen.log")))
      (System/exit exit))))

(defn run [command-line-args]
  (let [{:keys [args opts]} (cli/parse-args command-line-args
                                            {:spec cli-spec
                                             :error-fn (fn [{:keys [msg]}]
                                                         (exit! (str msg "\n" usage)))})
        [challenge-name] args
        challenges (read-challenges)
        challenge (get challenges challenge-name)]
    (when-not challenge
      (exit! (str usage "\nChallenges: " (string/join ", " (sort (keys challenges))))))
    (let [options (assoc opts
                         :challenge-name challenge-name
                         :challenge challenge
                         :challenge-ns (:challenge/ns challenge))]
      (check-tools! options)
      (when (= "graal" (:runner opts))
        (build-native! options))
      (case (:mode opts)
        "direct" (run-direct! options)
        "maelstrom" (run-maelstrom! options)))))
