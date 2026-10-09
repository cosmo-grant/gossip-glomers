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

(def cli-spec
  {:mode {:default "maelstrom"
          :validate #{"maelstrom" "direct"}}
   :runner {:default "bb"
            :validate #{"bb" "lein"}}})

(def usage
  "Usage: bb eval <name> [--mode maelstrom|direct] [--runner bb|lein]")

(defn print-err! [message]
  (binding [*out* *err*]
    (println message)))

(defn exit! [message]
  (print-err! message)
  (System/exit 1))

(defn missing-tools [{:keys [mode runner]}]
  (cond-> []
    (not (fs/which runner))
    (conj (str runner " (not on PATH)"))

    (and (or (= "lein" runner)
             (= "maelstrom" mode))
         (not (fs/which "java")))
    (conj "java (not on PATH)")

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

(defn node-command [runner challenge-ns]
  (case runner
    "bb" ["bb" "-m" (str challenge-ns)]
    "lein" ["lein" "with-profile" "-dev" "run" "-m" (str challenge-ns)]))

(defn write-wrapper! [{:keys [challenge-name runner challenge-ns]}]
  (let [path (fs/path "target" "bin" (str challenge-name "-" runner ".sh"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path)
          (str "#!/bin/sh\n"
               "cd \"" (fs/canonicalize ".") "\"\n"
               "exec " (string/join " " (node-command runner challenge-ns)) "\n"))
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    (str path)))

(defn shell! [command]
  (print-err! (str "$ " (string/join " " command)))
  (let [{:keys [exit]} (apply process/shell {:continue true} command)]
    (System/exit exit)))

(defn run-direct! [{:keys [runner challenge-ns]}]
  (shell! (node-command runner challenge-ns)))

(defn run-maelstrom! [{:keys [runner challenge challenge-ns] :as options}]
  (let [wrapper (write-wrapper! options)]
    (print-err! (str "# " wrapper " runs: " (string/join " " (node-command runner challenge-ns))))
    (shell! (concat [maelstrom-path "test"
                     "-w" (:challenge/workload challenge)
                     "--bin" wrapper]
                    (:challenge/maelstrom-args challenge)))))

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
      (case (:mode opts)
        "direct" (run-direct! options)
        "maelstrom" (run-maelstrom! options)))))
