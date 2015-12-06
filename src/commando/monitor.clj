(ns commando.monitor
  (:require [clojure.core.async :as async :refer [go chan >! >!! <! <!! pub sub mult]]
            [commando.protos.protos :as protos :refer [InfoProducer]]
            [clojure.core.match :refer [match]]))

;; ==========================================================================================
;; DataStore
;; A DataStore is where data can be sent to, processed and set to other processes
;; Producer processes will likely be sending its information to a DataStore
;;
;; The DataStore creates a publisher channel from data-channel.  Another producer of data will send data
;; to the DataStore's data-channel in the format of {:topic _ :message _}.  A consumer can listen to these
;; messages by subscribing to the publisher, and receiving any messages it might have
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


(defn make->DataStore
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
  "Extracts message from the publisher channel, and sends the message to its destination"
  ;; FIXME: this function is really doing 2 things.  it is extracting a message from the channel
  ;; calling the hdlr function to process the message, and then send the message to its final
  ;; destination.  This is really 2 processes: A) extract and process, B) send to final destination
  [chan dest hdlr]
  (go
    (loop [msg (<! chan)]
      (when msg
        ;; FIXME: we are assuming :message is a string
        (let [content (clojure.string/trim-newline (:message msg))
              content (if hdlr
                        (hdlr content)
                        content)]
          (match [dest]
                 [{:stdout out}] (println content)
                 [{:file fpath}] (spit fpath content)
                 [{:socket sock}] nil                       ;; TODO
                 [{:channel mchan}] (>! mchan content)))
        (recur (<! chan))))))

(defrecord DataTap
  [in-channel                                               ;; channel to pull messages from
   data-channel                                              ;; where messages from publisher will go
   topic                                                    ;; topic this Datatap receives
   destination                                              ;; where to send processed message to
   handler
   process                                                  ;; a function that takes a channel and a destination
   ])


(defn make->DataTap
  "A DataTap extracts messages from a publisher channel, processes the messages via a handler, and sends the
  message to a final destination"
  [publisher & {:keys [in-channel data-channel topic destination handler process]
                :or   {topic :stdout
                       data-channel (chan 10)
                       destination {:stdout *out*}
                       process sys-write}}]
  ;; Subscribe to the topic we're interested in
  (sub publisher topic data-channel)
  (let [dt (->DataTap in-channel data-channel topic destination handler process)
        pfn (:process dt)]
    (pfn data-channel destination handler)
    dt))
