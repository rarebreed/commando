(ns commando.monitor
  (:require [clojure.core.async :as async :refer [go chan >! >!! <! <!! pub sub mult]]
            [commando.protos.protos :as protos :refer [InfoProducer]]))

;; ==========================================================================================
;; DataSource
;; A DataSource is where data can be sent to, processed and set to other processes
;; Process will likely be producing information and sending to a DataStore
;; ==========================================================================================
(defrecord DataStore
  [data-channel                   ;; core.async channel to put data into
   publisher                      ;; core.async publisher derived from data-channel
   transformer                    ;; a function that possibly transforms a line before being put into log-channel
   topics                         ;; a list of topics that interested parties can subscribe to
   destinations                   ;; a list of destinations that data can go to (fan out)
   ])


(def destination-types
  {:stdout "Goes to the stdout of the main process"
   :file "a logconsumer that will send consumed messages to a file"
   :socket "consumed messages will go to a SocketChannel"
   :channel "consumed messages will to another channel for processing"})


(defn make->DataSource
  [& {:keys [size data-channel publisher transformer topics destinations]
      :or {size 10
           topics []
           destinations [:stdout]}}]
  (let [data-channel (if data-channel
                      data-channel
                      (-> (chan size)))
        publisher (if publisher publisher (pub data-channel :topic))
        topics (atom topics)
        destinations (atom destinations)]
    (->DataStore data-channel publisher transformer topics destinations)))

;; ==========================================================================================
;; DataTap
;; A DataTap is a process which takes data out of a channel and does something with it
;; a Process which needs to examine log information or events should probably use this
;; ==========================================================================================
(defn sys-write
  [chan]
  (go
    (loop [msg (<! chan)]
      (when msg
        (println (clojure.string/trim-newline (:message msg)))
        (recur (<! chan))))))


(defrecord DataTap
  [in-channel                                               ;; channel to pull messages from
   out-channel                                              ;; where messages from publisher will go
   topic                                                    ;; topic this Datatap receives
   destination                                              ;; where to send processed message to
   process                                                ;; a function that takes a channel
   ])


(defn make->DataTap
  [publisher & {:keys [in-channel data-channel topic destination process]
                :or   {topic        :stdout
                       data-channel (chan 10)
                       process      sys-write}}]
  (sub publisher topic data-channel)
  (let [dt (->DataTap in-channel data-channel topic destination process)
        pfn (:process dt)]
    (pfn data-channel)
    dt))
