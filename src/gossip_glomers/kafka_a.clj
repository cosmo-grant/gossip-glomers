(ns gossip-glomers.kafka-a
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require
   [hyperfiddle.rcf :as rcf]
   [gossip-glomers.util :as util]))

(def *me (atom nil))

(def *msg-id-counter (atom 1))

(def *reply-promises (atom {})) ;; msg-id -> promise

#_{:k1 {:items [1 2 3]
        :commit-offset 2}
   :k2 {:items [4 5 6]
        :commit-offset 1}}
(def *streams (atom {}))

(defn with-id
  [proto-message]
  (let [msg-id (swap! *msg-id-counter inc)]
    (assoc-in proto-message [:body :msg_id] msg-id)))

(defn init [in]
  (util/log-set-id! (get-in in [:body :node_id]))
  (reset! *me (get-in in [:body :node_id]))
  (util/send! (with-id (util/reply in {:type "init_ok"}))))

(defn reply-handler [in]
  (let [reply-msg-id (get-in in [:body :in_reply_to])
        [before _] (swap-vals! *reply-promises dissoc reply-msg-id)
        p (get before reply-msg-id)]
    (when p
      (deliver p in))))

(defn send [in]
  ;; in
  #_{:key "k1"
     :msg 123}
  ;; reply
  #_{:offset 1000}
  (let [{k :key item :msg} (:body in)
        k (keyword k)
        streams (swap! *streams update-in [k :items] (fnil conj []) item)
        offset (dec (count (get-in streams [k :items])))]
    (util/send! (with-id (util/reply in {:type "send_ok"
                                         :offset offset})))))
(defn poll [in]
  ;; in
  #_{:offsets {:k1 1000 :k2 2000}}
  ;; reply
  #_{:msgs {:k1 [[1000 9] ,,,]
            :k2 [[2000 8] ,,,]}}
  (let [streams @*streams
        key-offset-pairs (get-in in [:body :offsets])
        indexed-items-by-key (->> key-offset-pairs
                                  (map (fn [[k offset]]
                                         [k (->> (get-in streams [k :items])
                                                 (drop offset)
                                                 (map-indexed (fn [i v]
                                                                [(+ offset i) v])))]))
                                  (into {}))]
    (util/send! (with-id (util/reply in {:type "poll_ok"
                                         :msgs indexed-items-by-key})))))


(rcf/tests
 (merge-with merge
             {:k1 {:items [1 2 3]
                   :commit-offset 2}
              :k2 {:items [4 5 6]
                   :commit-offset 1}
              :k3 {:items [4 5]
                   :commit-offset 0}}
             {:k2 {:commit-offset 4}
              :k3 {:commit-offset 5}})
 :=
 {:k1 {:items [1 2 3]
       :commit-offset 2}
  :k2 {:items [4 5 6]
       :commit-offset 4}
  :k3 {:items [4 5]
       :commit-offset 5}})

(defn commit-offsets [in]
  ;; in
  #_{:offsets {:k1 1000 :k2 2000}}
  ;; reply
  #_{}
  (let [offsets (get-in in [:body :offsets])]
    (swap! *streams (fn [streams]
                      (merge-with merge
                                  streams
                                  (update-vals offsets (fn [v] {:commit-offset v}))))))
  (util/send! (with-id (util/reply in {:type "commit_offsets_ok"}))))


(defn list-committed-offsets [in]
  ;; in
  #_{:keys ["k1" "k2"]}
  ;; reply
  #_{:offsets {:k1 1000 :k2 2000}}
  (tap> [:LIST_COMMITED_OFFSETS @*streams])
  (let [streams @*streams
        offsets-by-key (->> (get-in in [:body :keys])
                            ;; we get them as strings
                            (map keyword)
                            (filter (fn [k]
                                      (contains? streams k)))
                            (map (fn [k]
                                   [k (get-in streams [k :commit-offset] 0)]))
                            (into {}))]
    (util/send! (with-id (util/reply in {:type "list_committed_offsets_ok"
                                         :offsets offsets-by-key})))))

(defn handler [in]
  (tap> [:RECV in])

  (case (get-in in [:body :type])
    "init" (init in)
    "send" (send in)
    "poll" (poll in)
    "commit_offsets" (commit-offsets in)
    "list_committed_offsets" (list-committed-offsets in)
    nil))

(defn -main [& _]
  (util/handle-messages-v2 handler))


