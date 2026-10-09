(ns gossip-glomers.echo

  (:require [cheshire.core :as json]
            [hyperfiddle.rcf :as rcf]))

(defn init [in]
  {:src (:dest in)
   :dest (:src in)
   :body {:type "init_ok"
          :in_reply_to (get-in in [:body :msg_id])}})

(defn echo [in]
  {:src (:dest in)
   :dest (:src in)
   :body {:type "echo_ok"
          :msg_id 1
          :in_reply_to (get-in in [:body :msg_id])
          :echo (get-in in [:body :echo])}})

(defn handler [in]
  (case (get-in in [:body, :type])
    "init" (init in)
    "echo" (echo in)))

(defn -main [& _]
  (loop [] 
    (when-let [line (read-line)]
      (-> line
          (json/parse-string true)
          handler
          json/generate-string
          println) 
      (recur))))

(rcf/tests
 (init
  {:src "c1",
   :dest "n1",
   :body {:type "init",
          :msg_id 1}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "init_ok",
         :in_reply_to 1}}

 (echo
  {:src "c1",
   :dest "n1",
   :body {:type "echo",
          :msg_id 1,
          :echo "Please echo 35"}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "echo_ok",
         :msg_id 1,
         :in_reply_to 1,
         :echo "Please echo 35"}})

