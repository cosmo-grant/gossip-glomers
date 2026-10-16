(ns gossip-glomers.unique-ids
  (:require
   [hyperfiddle.rcf :as rcf]
   [gossip-glomers.util :as util]))

(defn init [in]
  (util/reply in {:type "init_ok"}))

(def *counter (atom 0))

(defn generate [in]
  (let [counter (swap! *counter inc)]
    (util/reply in {:type "generate_ok"
                    :msg_id counter
                    :id [(:dest in) counter]})))

(defn handler [in]
  (case (get-in in [:body :type])
    "init" (init in)
    "generate" (generate in)))

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

