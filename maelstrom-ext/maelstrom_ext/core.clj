(ns maelstrom-ext.core
  (:gen-class)
  (:require
   [jepsen.cli :as cli]
   [maelstrom.core :as maelstrom]
   [maelstrom.workload.broadcast :as broadcast]
   [maelstrom-ext.single-broadcast :as single-broadcast]))

(alter-var-root #'maelstrom/workloads assoc :single-broadcast single-broadcast/workload)

(alter-var-root #'broadcast/topologies assoc :tree5 (fn [test]
                                                      (broadcast/tree-topology 5 test)))

;; maelstrom's opt specs captured the original workloads and topologies maps when they loaded
(def test-opt-spec
  [[nil "--bin FILE" "Path to binary which runs a node"
    :missing "Expected a --bin PATH_TO_BINARY to test"]

   ["-w" "--workload NAME" "What workload to run."
    :default :lin-kv
    :parse-fn keyword
    :validate [maelstrom/workloads (cli/one-of maelstrom/workloads)]]

   [nil "--origin NODE" "single-broadcast: which node receives the broadcast"
    :default "n0"]

   [nil "--topology SPEC" "What kind of network topology to offer to nodes, for those workloads (e.g. broadcast) which use one."
    :parse-fn keyword
    :default :grid
    :validate [broadcast/topologies (cli/one-of broadcast/topologies)]]])

(def opt-spec
  (remove (fn [[_short long-opt]]
            (re-find #"^--topology" long-opt))
          maelstrom/opt-spec))

(defn -main [& args]
  (if (= "export" (first args))
    (let [[store-dir value] (rest args)]
      (println (single-broadcast/export-journal! {:store-dir store-dir
                                                  :value (some-> value parse-long)})))
    (cli/run! (cli/single-test-cmd {:test-fn maelstrom/maelstrom-test
                                    :opt-spec (concat test-opt-spec opt-spec)
                                    :opt-fn* maelstrom/opt-fn})
              args)))
