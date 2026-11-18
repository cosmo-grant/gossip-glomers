(ns gossip-glomers.broadcast-e
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require
   [gossip-glomers.util :as util]
   [clojure.set :as set]))

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
(def *items-to-send (atom #{}))
(def *messages-sent-but-unconfirmed (atom {}))    ;; and not resent...
(def *msg-id-counter (atom 1))
(def broadcast-period-ms 80)  ;; the lower this, the higher msgs-per-op and the lower the latencies
;; for total topology, 1000 did well
;; for tree4, 500 breaks latencies
;; for tree4, 80 does well

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

(defn send-queued-items! []
  (util/vthread
   (Thread/sleep broadcast-period-ms)
   (let [[items-to-send, _] (reset-vals! *items-to-send #{})]
     (doseq [n @*neighbors]
      (durable-send! {:src @*me
                      :dest n
                      :body {:type "gossip"
                               :items items-to-send}})))
                     
   (send-queued-items!)))

(defn init [in]
  (send-queued-items!)
  (reset! *me (get-in in [:body :node_id]))
  (util/send! (util/reply in {:type "init_ok"})))

(defn gossip_ok [in]
  (let [msg-id (get-in in [:body :in_reply_to])]
    (swap! *messages-sent-but-unconfirmed dissoc msg-id)))

(defn broadcast [in]
  (let [item (get-in in [:body :message])]
    (when-not (contains? @*items-seen item)
      (swap! *items-seen conj item)
      (swap! *items-to-send conj item)))
  (util/send! (util/reply in {:type "broadcast_ok"})))

(defn gossip [in]
  (let [items (set (get-in in [:body :items]))
        new-items (set/difference items @*items-seen)]
    (swap! *items-seen set/union new-items)
    (swap! *items-to-send set/union new-items))
  (util/send! (util/reply in {:type "gossip_ok"})))

(defn read [in]
  (util/send! (util/reply in {:type "read_ok" :messages @*items-seen})))

(defn topology [in]
  (reset! *neighbors (set (get-in in [:body :topology (keyword @*me)])))
  (util/send! (util/reply in {:type "topology_ok"})))

(defn handler [in]
  (case (get-in in [:body :type])
    "init" (init in)
    "broadcast" (broadcast in)
    "gossip" (gossip in)
    "gossip_ok" (gossip_ok in)
    "read" (read in)
    "topology" (topology in)
    nil))

(defn -main [& _]
  (util/handle-messages-v2 handler))

;; reply broadcast_ok immediately, before sending to neighbours
;; switch to java
;; don't reuse message ids
