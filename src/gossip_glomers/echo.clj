(ns gossip-glomers.echo
  (:require
   [hyperfiddle.rcf :as rcf]
   [gossip-glomers.util :as util]))

(defn init [in]
  (util/reply in {:type "init_ok"}))

(defn echo [in]
  (util/reply in {:type "echo_ok"
                  :echo (get-in in [:body :echo])
                  :msg_id 1}))

(defn handler [in]
  (case (get-in in [:body :type])
    "init" (init in)
    "echo" (echo in)))

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

 "echo"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "echo"
          :msg_id 1,
          :echo "Please echo 35"}})
 :=
 {:src "n1",
  :dest "c1",
  :body {:type "echo_ok",
         :msg_id 1,
         :in_reply_to 1,
         :echo "Please echo 35"}})

