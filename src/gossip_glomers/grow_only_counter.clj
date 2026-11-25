(ns gossip-glomers.grow-only-counter
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require
   [gossip-glomers.util :as util]))

;; init

;; read
;;   read from the service
;;   wait for reply
;;   reply

;; add
;;   loop:
;;     read from the service
;;     wait for reply
;;     cas with the service using the read value and the requested addition value
;;     if unsucessefully, loop; oterhwise break

(def global-counter-key "counter")
(def *me (atom nil))
(def *neighbors (atom #{}))
(def *msg-id-counter (atom 1))

(defn with-id
  [proto-message]
  (let [msg-id (swap! *msg-id-counter inc)]
    (assoc-in proto-message [:body :msg_id] msg-id)))

(defn init [in]
  (util/log-set-id! (get-in in [:body :node_id]))
  (reset! *me (get-in in [:body :node_id]))
  (util/send! (with-id (util/reply in {:type "init_ok"}))))

(def *reply-promises (atom {})) ;; msg-id -> promise

(defn promise-send! [message]
   (let [p (promise)]
     (swap! *reply-promises assoc (get-in message [:body :msg_id]) p)
     (util/send! message)
     p))

(defn read [in]
  ;; on a new thread, because we block in wait of the reply
  (util/vthread
   (let [;; write a unique value to <me>-inc key
         ;; which forces this nodes kv state to catch up so subsequent read is up to date
         ;; this is still only 'eventually consistent', because another node might be asked to write after this write
         _ @(promise-send! (with-id {:src @*me
                                     :dest "seq-kv"
                                     :body {:type "write"
                                            :key (str @*me "-inc")
                                            ;; consuming a msg-id
                                            :value (swap! *msg-id-counter inc)}}))
         response @(promise-send! (with-id {:src @*me
                                            :dest "seq-kv"
                                            :body {:type "read"
                                                   :key global-counter-key}}))
         counter-value (get-in response [:body :value]
                               ;; default
                               0)
         ]
     (tap> [:HERE counter-value])
     (util/send! (with-id (util/reply in {:type "read_ok"
                                          :value counter-value}))))))

(defn add [in]
  ;; on a new thread, because we block in wait of the reply
  (util/vthread
   (let [delta (get-in in [:body :delta])
         ;; could cache counter-value to save a read sometimes
         ;;   ie. do CAS w/ cached-value first, and then read and try again as fallback
         response @(promise-send! (with-id {:src @*me
                                            :dest "seq-kv"
                                            :body {:type "read"
                                                   :key global-counter-key}}))
         counter-value (get-in response [:body :value]
                               ;; default
                               0)
         response @(promise-send! (with-id {:src @*me
                                            :dest "seq-kv"
                                            :body {:type "cas"
                                                   :key global-counter-key
                                                   :from counter-value
                                                   :to (+ counter-value delta)
                                                   :create_if_not_exists true}}))]
     (if (get-in response [:body :code])
       ;; cas error, we're out of date, try again
       (add in)
       (util/send! (with-id (util/reply in {:type "add_ok"})))))))

(defn reply-handler [in]
  (let [reply-msg-id (get-in in [:body :in_reply_to])
        [before _] (swap-vals! *reply-promises dissoc reply-msg-id)
        p (get before reply-msg-id)]
    (when p
      (deliver p in))))

(defn topology [in]
  (reset! *neighbors (set (get-in in [:body :topology (keyword @*me)])))
  (util/send! (with-id (util/reply in {:type "topology_ok"}))))

(defn handler [in]
  (tap> [:RECV in])

  (case (get-in in [:body :type])
    "init" (init in)
    "add" (add in)
    "read" (read in)
    "topology" (topology in)
    "read_ok" (reply-handler in)
    "cas_ok" (reply-handler in)
    "write_ok" (reply-handler in)
    "error" (reply-handler in)
    nil))

(defn -main [& _]
  (util/handle-messages-v2 handler))


