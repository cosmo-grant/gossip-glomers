(ns gossip-glomers.grow-only-counter
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require
   [gossip-glomers.util :as util]
   [clojure.set :as set]))

;;;;; S 1

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



;;;;; S 2 - caching the read value

;; init
;;   keep our own counter atom
;;   start loop
;;     read from kv service, update our counter atom

;; read
;;   read from our counter atom

;; add
;;   cas with the service using our counter atom and the requested addition value


(def global-counter-key "counter")
(def *me (atom nil))
(def *neighbors (atom #{}))
(def *messages-sent-but-unconfirmed (atom {}))    ;; and not resent...
(def *msg-id-counter (atom 1))

(defn with-id
  [proto-message]
  (let [msg-id (swap! *msg-id-counter inc)]
    (assoc-in proto-message [:body :msg_id] msg-id)))

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
   #_(Thread/sleep 50)
   (let [response @(promise-send! (with-id {:src @*me
                                            :dest "seq-kv"
                                            :body {:type "read"
                                                   :key global-counter-key}}))
         counter-value (get-in response [:body :value]
                               ;; default
                               0)
         ;; CAS, to check if our read is stale
         ;; DOES THIS REALLY DO ANTYHING, OTHER THAN A DELAY
         response @(promise-send! (with-id {:src @*me
                                            :dest "seq-kv"
                                            :body {:type "cas"
                                                   :key global-counter-key
                                                   :from counter-value
                                                   :to counter-value
                                                   :create_if_not_exists true}}))]
     #_(util/send! (with-id (util/reply in {:type "read_ok"
                                          :value counter-value})))
     (if (get-in response [:body :code])
       ;; cas error, we're out of date, try again
       (read in)
       (util/send! (with-id (util/reply in {:type "read_ok"
                                            :value counter-value})))))))

(defn add [in]
  ;; on a new thread, because we block in wait of the reply
  (util/vthread
   (let [delta (get-in in [:body :delta])
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
       (do
         #_(Thread/sleep 100)
         (add in))
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
    "error" (reply-handler in)
    nil))

(defn -main [& _]
  (util/handle-messages-v2 handler))


