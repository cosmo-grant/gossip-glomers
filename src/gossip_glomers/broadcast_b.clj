(ns gossip-glomers.broadcast-b
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require
   [hyperfiddle.rcf :as rcf]
   [gossip-glomers.util :as util]))

;; Strategy (assumes no partitions, reliable network):
;;   when i receive a message:
;;     check against messages we've seen
;;     if not found:
;;       add it to our messages
;;       broadcast to all neighbours minus sender

(def *messages (atom #{}))
(def *neighbors (atom #{}))
(def *me (atom nil))

(defn init [in]
  (reset! *me (get-in in [:body :node_id]))
  (util/reply in {:type "init_ok"}))

(defn broadcast [in]
  (let [message (get-in in [:body :message])]
    (when-not (contains? @*messages message)
      (swap! *messages conj message)
      (doseq [n (disj @*neighbors (:src in))]
        (util/send! {:src @*me
                     :dest n
                     :body {:type "broadcast"
                            :message message}}))))

  (util/reply in {:type "broadcast_ok"}))

(defn read [in]
  (util/reply in {:type "read_ok" :messages @*messages}))

(defn topology [in]
  (reset! *neighbors (set (get-in in [:body :topology (keyword @*me)])))
  (util/reply in {:type "topology_ok"}))

(defn handler [in]
  (case (get-in in [:body :type])
    "init" (init in)
    "broadcast" (broadcast in)
    "read" (read in)
    "topology" (topology in)
    nil))

(defn -main [& _]
  (util/handle-messages handler))

(rcf/tests
 "init"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "init",
          :msg_id 1}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "init_ok",
         :in_reply_to 1}}

 "broadcast"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "broadcast"
          :msg_id 1
          :message 666}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "broadcast_ok",
         :in_reply_to 1}}

 "read"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "read"
          :msg_id 1}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "read_ok",
         :messages #{666}
         :in_reply_to 1}})
