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
  "/Users/rafal/Downloads/BIG/graalvm-jdk-25.0.3+9.1/Contents/Home/bin/native-image")

(def graal-jar-path "target/gossip-glomers-graal.jar")

(def jvm-jar-path "target/gossip-glomers-jvm.jar")

(def jvm-opts
  [;; Single-threaded GC with the smallest footprint. The default (G1) adds
   ;; remembered sets and per-core GC threads. With 25 node JVMs on one
   ;; machine, parallel GC threads compete for the same cores. Node heaps
   ;; are small, so serial pauses are short.
   "-XX:+UseSerialGC"
   ;; Max heap. The default is 1/4 of physical RAM per JVM, so 25 nodes can
   ;; each grow large before GC runs. Node state (seen messages, pending
   ;; retries) fits in a few MB. Raise this if a challenge holds more state.
   "-Xmx64m"
   ;; Thread stack size (default 1MB). Platform threads (main, GC, JIT)
   ;; reserve less memory. Virtual threads do not use this; their stacks
   ;; live on the heap.
   "-Xss512k"
   ;; Use only the C1 (client) JIT; never C2. C2 makes faster code, but it
   ;; needs much CPU and memory to compile. Nodes run for seconds and spend
   ;; most time waiting on I/O, so C2 does not pay back its cost.
   "-XX:TieredStopAtLevel=1"
   ;; One JIT compiler thread instead of one per few cores. This limits CPU
   ;; contention between the 25 JVMs at startup, when all compile at once.
   "-XX:CICompilerCount=1"])

(def aot-training-input
  "{\"src\":\"c0\",\"dest\":\"n0\",\"body\":{\"type\":\"init\",\"msg_id\":1,\"node_id\":\"n0\",\"node_ids\":[\"n0\"]}}\n")

(def cli-spec
  {:mode {:default "maelstrom"
          :validate #{"maelstrom" "direct"}}
   :runner {:default "bb"
            :validate #{"bb" "lein" "graal" "uberjar"}}
   :quiet {:coerce :boolean}})

(def usage
  "Usage: bb eval <name> [--mode maelstrom|direct] [--runner bb|lein|graal|uberjar] [--quiet]")

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

    (and (#{"lein" "graal" "uberjar"} runner)
         (not (fs/which "lein")))
    (conj "lein (not on PATH)")

    (and (or (#{"lein" "graal" "uberjar"} runner)
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

(defn aot-cache-path [challenge-ns]
  (str (fs/path "target" "jvm" (str challenge-ns ".aot"))))

(defn java-command [challenge-ns extra-opts]
  (concat ["java"]
          jvm-opts
          extra-opts
          ["-cp" jvm-jar-path (munge (str challenge-ns))]))

(defn node-command [runner challenge-ns]
  (case runner
    "bb" ["bb" "-m" (str challenge-ns)]
    "lein" ["lein" "with-profile" "-dev" "run" "-m" (str challenge-ns)]
    "graal" [(native-binary-path challenge-ns)]
    "uberjar" (java-command challenge-ns [(str "-XX:AOTCache=" (aot-cache-path challenge-ns))])))

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

(defn build-uberjar! [{:keys [challenge-ns]}]
  (let [aot-cache (aot-cache-path challenge-ns)]
    (when (stale? jvm-jar-path ["src" "project.clj"])
      (shell-or-exit! ["lein" "with-profile" "-dev,+jvm" "uberjar"]))
    (when (stale? aot-cache [jvm-jar-path])
      (fs/create-dirs (fs/parent aot-cache))
      (let [command (java-command challenge-ns [(str "-XX:AOTCacheOutput=" aot-cache)])]
        (print-err! (str "$ " (string/join " " command) " < init message"))
        (apply process/shell {:in aot-training-input :out :discard} command)))))

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
      (case (:runner opts)
        "graal" (build-native! options)
        "uberjar" (build-uberjar! options)
        nil)
      (case (:mode opts)
        "direct" (run-direct! options)
        "maelstrom" (run-maelstrom! options)))))
