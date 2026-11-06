(ns gossip-glomers.broadcast-d
  (:refer-clojure :exclude [read])
  (:require
   [gossip-glomers.util :as util]))

;; Strategy (assumes no partitions, reliable network):
;;   when i receive a message:
;;     check against messages we've seen
;;     if not found:
;;       add it to our messages
;;       broadcast to all neighbours minus sender

;; for fault-tolerance:
;;     - per message, check after 1 second if it was confirmed, if not, resend

(def *me (atom nil))
(def *neighbors (atom #{}))
(def *items-seen (atom #{}))
(def *messages-sent-but-unconfirmed (atom {}))
(def *msg-id-counter (atom 1))

(defn init [in]
  (reset! *me (get-in in [:body :node_id]))
  (util/send! (util/reply in {:type "init_ok"})))

(defn durable-send!
  [payload]
  (let [ ;; always use fresh message id
        payload (let [msg-id (swap! *msg-id-counter inc)]
                  (assoc-in payload [:body :msg_id] msg-id))
        msg-id (get-in payload [:body :msg_id])]
    (swap! *messages-sent-but-unconfirmed assoc msg-id payload)
    (util/send! payload)
    ;; attempt resend in future
    ;; note: this will spawn threads equal to number of unacknowledged requests
    (util/vthread
     (Thread/sleep 1000)
     (when-let [payload (get @*messages-sent-but-unconfirmed msg-id)]
       (swap! *messages-sent-but-unconfirmed dissoc msg-id)
       (durable-send! payload)))))

(defn broadcast_ok [in]
  (let [msg-id (get-in in [:body :in_reply_to])]
    (swap! *messages-sent-but-unconfirmed dissoc msg-id)))

(defn broadcast [in]
  (util/send! (util/reply in {:type "broadcast_ok"}))
  (let [message (get-in in [:body :message])]
    (when-not (contains? @*items-seen message)
      (swap! *items-seen conj message)
      (doseq [n (disj @*neighbors (:src in))]
        (durable-send! {:src @*me
                        :dest n
                        :body {:type "broadcast"
                               :message message}})))))

(defn read [in]
  (util/send! (util/reply in {:type "read_ok" :messages @*items-seen})))

(defn topology [in]
  (reset! *neighbors (set (get-in in [:body :topology (keyword @*me)])))
  (util/send! (util/reply in {:type "topology_ok"})))

(defn handler [in]
  (case (get-in in [:body :type])
    "init" (init in)
    "broadcast" (broadcast in)
    "broadcast_ok" (broadcast_ok in)
    "read" (read in)
    "topology" (topology in)
    nil))

(defn -main [& _]
  (util/handle-messages-v2 handler))

;; reply broadcast_ok immediately, before sending to neighbours
;; switch to java
;; don't reuse message ids
