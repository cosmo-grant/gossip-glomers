(ns maelstrom-ext.single-broadcast
  (:require
   [clojure.data.fressian :as fressian]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [jepsen.checker :as checker]
   [jepsen.generator :as gen]
   [jepsen.store :as store]
   [maelstrom.net.journal :as journal]
   [maelstrom.workload.broadcast :as broadcast]))

(defn read-journal-file [file]
  (with-open [stream (io/input-stream file)]
    (->> (fressian/create-reader stream :handlers journal/read-handlers)
         journal/reader-seq
         doall)))

(defn read-journal [journal-dir]
  (->> (file-seq (io/file journal-dir))
       (filter (fn [file]
                 (re-find #"\.fressian$" (.getName ^java.io.File file))))
       (mapcat read-journal-file)
       (sort-by :id)))

(defn event->json [start-nanos event]
  (let [message (:message event)]
    {:id (:id event)
     :t_ms (/ (- (:time event) start-nanos) 1e6)
     :type (:type event)
     :message_id (:id message)
     :src (:src message)
     :dest (:dest message)
     :body (:body message)}))

(defn broadcast-of-value? [value body]
  (and (= "broadcast" (name (:type body)))
       (= value (:message body))))

(defn only-value
  "Keeps topology messages, broadcasts of value, and the broadcast_oks replying to them."
  [value events]
  (let [broadcast-keys (->> events
                            (filter (fn [event]
                                      (broadcast-of-value? value (get-in event [:message :body]))))
                            (map (fn [event]
                                   [(get-in event [:message :src])
                                    (get-in event [:message :body :msg_id])]))
                            set)]
    (filter (fn [{:keys [message]}]
              (let [body (:body message)]
                (case (name (:type body))
                  ("topology" "topology_ok") true
                  "broadcast" (broadcast-of-value? value body)
                  "broadcast_ok" (contains? broadcast-keys [(:dest message) (:in_reply_to body)])
                  false)))
            events)))

(defn export-journal!
  "Writes trace.json (for viz/broadcast.html) next to net-journal in a maelstrom store dir."
  [{:keys [store-dir value topology-name]}]
  (let [events (cond->> (read-journal (io/file store-dir "net-journal"))
                 value (only-value value))
        start-nanos (or (some->> events
                                 (map :time)
                                 seq
                                 (apply min))
                        0)
        nodes (->> events
                   (mapcat (fn [event]
                             [(get-in event [:message :src])
                              (get-in event [:message :dest])]))
                   (filter (fn [node-id]
                             (re-matches #"n\d+" node-id)))
                   distinct
                   (sort-by (fn [node-id]
                              (parse-long (subs node-id 1)))))
        out-file (io/file store-dir "trace.json")]
    (with-open [writer (io/writer out-file)]
      (json/generate-stream {:nodes nodes
                             :topology_name topology-name
                             :events (map (fn [event]
                                            (event->json start-nanos event))
                                          events)}
                            writer))
    (str out-file)))

(defn trace-exporter []
  (reify checker/Checker
    (check [_ test _history _opts]
      {:valid? true
       :file (export-journal! {:store-dir (store/path test)
                               :topology-name (some-> (:topology test) name)})})))

(defn origin-thread [{:keys [nodes origin]}]
  (let [index (.indexOf ^java.util.List (vec nodes) origin)]
    (when (neg? index)
      (throw (ex-info (str "--origin " origin " is not one of the nodes") {:origin origin})))
    index))

(defn workload [opts]
  {:client (broadcast/client (:net opts))
   :generator (gen/on-threads #{(origin-thread opts)}
                              (gen/once {:f :broadcast
                                         :value 0}))
   :final-generator (gen/each-thread {:f :read
                                      :final? true})
   :checker (checker/compose {:broadcast (broadcast/checker)
                              :trace (trace-exporter)})})
