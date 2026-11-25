(ns gossip-glomers.util
  (:require
   [cheshire.core :as json]))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ t e]
                      (tap> [:THREAD-ERROR (.getName t) e]))))

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
    (println value)))

(defn send! [message]
  {:pre [(some? (get-in message [:body :msg_id]))]}
  (tap> [:SEND message])
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



(defonce log-file (atom nil))
(defn log-set-id! [id]
  (reset! log-file
          (clojure.java.io/writer (str "debug_" id ".txt")))
  (add-tap (fn [v]
             (binding [*out* @log-file]
               (prn v))))
  nil)

