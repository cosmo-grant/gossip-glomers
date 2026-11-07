(ns gossip-glomers.broadcast-a
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require
   [hyperfiddle.rcf :as rcf]
   [gossip-glomers.util :as util]))

(defn init [in]
  (util/reply in {:type "init_ok"}))

(def *messages (atom []))

(defn broadcast [in]
  ;; (swap! *messages (fn [messages] (conj message (get-in in [:body :message]))))
  (swap! *messages conj (get-in in [:body :message]))
  (util/reply in {:type "broadcast_ok"}))

(defn read [in]
  (util/reply in {:type "read_ok" :messages @*messages}))

(defn topology [in]
  (util/reply in {:type "topology_ok"}))

(defn handler [in]
  (case (get-in in [:body :type])
    "init" (init in)
    "broadcast" (broadcast in)
    "read" (read in)
    "topology" (topology in)))

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

 "broadcast"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "broadcast"
          :msg_id 1
          :message 666}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "broadcast_ok",
         :in_reply_to 1}}

 "read"
 (handler
  {:src "c1",
   :dest "n1",
   :body {:type "read"
          :msg_id 1}})

 :=
 {:src "n1",
  :dest "c1",
  :body {:type "read_ok",
         :messages [666]
         :in_reply_to 1}})


