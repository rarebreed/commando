(ns commando.monitor
  (:require [clojure.core.async :as async :refer [go chan !> !!> <! <!! pub sub mult]]
            [commando.protos.protos :refer [InfoProducer]]))

;; ==========================================================================================
;; LogProducer
;; A LogProducer is a process that emits data of some kind and puts it into a channel.  This
;; can be used by Process types in that they will likely be producing information of interest
;; to other Processes
;; ==========================================================================================
(defrecord LogProducer
  [;; core.async channel to put lines into
   out-channel
   ;; a function that possibly transforms a line before being put into log-channel
   transformer
   ;; a list of topics that interested parties can subscribe to
   topics])


(defn make->LogProducer
  [& {:keys [size out-channel transformer topics]
      :or {size 10}}]
  (let [out-channel (if out-channel
                      out-channel
                      (-> (chan size) (pub :topic)))
        topics (atom topics)]
    (->LogProducer out-channel transformer topics)))

;; ==========================================================================================
;; LogConsumer
;; A LogConsumer is a process which takes data out of a channel and does something with it
;; a Process which needs to examine log information or events should probably use this
;; ==========================================================================================
(defrecord LogConsumer
  [in-channel
   subscribed-to])

(defn make->LogConsumer
  [& {:keys [size in-channel subscribed-to]
      :or {size 10
           subscribed-to }}]
  (let [in-channel (chan size)]
    (doseq [subscription subscribed-to])))
