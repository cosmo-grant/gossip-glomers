(ns gossip-glomers.util
  (:require
   [cheshire.core :as json]))

(defmacro vthread [& body]
  `(Thread/startVirtualThread (fn [] ~@body)))

(defn reply [in body]
  {:src (:dest in)
   :dest (:src in)
   :body (assoc body :in_reply_to (get-in in [:body :msg_id]))})


;; we may be calling send! from multiple threads
;; println on its own is not thread-safe

(def o (Object.))
(defn print!
  [value]
  (locking o
    (tap> value)
    (println value)))

(defn send! [message]
  (->> message
       json/generate-string
       print!))

(defn handle-messages [handler]
  (loop []
    (when-let [line (read-line)]
      (some-> line
              (json/parse-string true)
              handler
              send!)
      (recur))))

(defn handle-messages-v2 [handler]
  (loop []
    (when-let [line (read-line)]
      (some-> line
              (json/parse-string true)
              handler)
      (recur))))
