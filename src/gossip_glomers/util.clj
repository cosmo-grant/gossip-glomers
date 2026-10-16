(ns gossip-glomers.util
  (:require
   [cheshire.core :as json]))

(defn reply [in body]
  {:src (:dest in)
   :dest (:src in)
   :body (assoc body :in_reply_to (get-in in [:body :msg_id]))})

(defn handle-messages [handler]
  (loop []
    (when-let [line (read-line)]
      (some-> line
              (json/parse-string true)
              handler
              json/generate-string
              println)
      (recur))))
