(ns gossip-glomers.unique-ids
  (:require
   [cheshire.core :as json]
   [hyperfiddle.rcf :as rcf]))

(defn init [in]
  {:src (:dest in)
   :dest (:src in)
   :body {:type "init_ok"
          :in_reply_to (get-in in [:body :msg_id])}})

(def *counter (atom 0))

(defn generate [in]
  (let [counter (swap! *counter inc)]
    {:src (:dest in)
     :dest (:src in)
     :body {:type "generate_ok"
            :msg_id counter
            :in_reply_to (get-in in [:body :msg_id])
            :id [(:dest in) counter]}}))

(defn handler [in]
  (case (get-in in [:body, :type])
    "init" (init in)
    "generate" (generate in)))

(defn -main [& _]
  (loop []
    (when-let [line (read-line)]
      (some-> line
          (json/parse-string true)
          handler
          json/generate-string
          println)
      (recur))))

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

 "generate"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "generate"
          :msg_id 1}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "generate_ok",
         :msg_id 1,
         :in_reply_to 1,
         :id ["n1" 1]}})

