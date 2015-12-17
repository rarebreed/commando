(ns commando.monitor
  (:require [clojure.core.async :as async :refer [go chan >! >!! <! <!! pub sub mult]]
            [commando.protos.protos :as protos :refer [InfoProducer]]
            [clojure.core.match :refer [match]]
            [taoensso.timbre :as timbre])
  (:import [java.nio.file.StandardWatchEventKinds ]))

;; TODO: get the current time
(defn timestamped-name
  [prefix]
  )

(def ^:dynamic *default-log* "/tmp/commando.log")

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
   ])


;; a map of possible destination types.
(def destination-types
  {:stdout "Goes to the stdout of the main process.  value is *out*"
   :file "a logconsumer that will send consumed messages to a file. value is path to file"
   :socket "consumed messages will go to a SocketChannel. value is a SocketChannel object"
   :channel "consumed messages will to another channel for processing. value is a core.async channel"
   :in-mem "stored in-memory (eg StringBuilder or a map). value is a data type"})


(defn make->DataStore
  "Creates a DataStore.  By default, it will create a 10 item channel and derive a publisher from it.
  It creates an empty vector of topics.  A user can create a multicast instead of publisher DataStore
  by setting the :ctype :multicaster"
  [& {:keys [size data-channel publisher transformer topics ctype]
      :or   {size         10
             ctype         :publisher
             topics       []}}]
  (let [data-channel (if data-channel
                       data-channel
                       (-> (chan size)))
        publisher (if publisher
                    publisher
                    (cond (= :publisher ctype) (pub data-channel :topic)
                          (= :multicaseter ctype) (mult data-channel)
                          :else (do
                                  (timbre/error "Invalid :ctype")
                                  (pub data-channel :topic))))
        topics (atom topics)]
    (->DataStore data-channel publisher transformer topics)))

;; ==========================================================================================
;; New implementation
;; ==========================================================================================
(defrecord DataBus
  [data-channel                   ;; core.async channel to put data into
   multicaster                    ;; core.async mult derived from data-channel
   transformer                    ;; a function that possibly transforms a line before being put into log-channel
   ])

(defn make->DataBus
  "Creates a DataStore.  By default, it will create a 10 item channel and derive a publisher from it.
  It creates an empty vector of topics.  A user can create a multicast instead of publisher DataStore
  by setting the :ctype :multicaster"
  [& {:keys [size data-channel multicaster transformer]
      :or   {size         10}}]
  (let [data-channel (if data-channel
                       data-channel
                       (-> (chan size)))
        multicaster (if multicaster
                      multicaster
                      (mult data-channel))]
    (->DataBus data-channel multicaster transformer)))


;; ==========================================================================================
;; DataTap
;; A DataTap is a process which takes data out of a channel and does something with it
;; a Process which needs to examine log information or events should probably use this
;; ==========================================================================================
(defn dispatcher
  "Extracts message from the publisher channel, and sends the message to its destination"
  ;; FIXME: this function is really doing 2 things.  it is extracting a message from the channel
  ;; calling the hdlr function to process the message, and then send the message to its final
  ;; destination.  This is really 2 processes: A) extract and process, B) send to final destination
  ;; FIXME: Probably better to make this into a protocol, and have it implemented for different
  ;; destination endpoints.  For example, extend-type StringBuilder DestWriter
  [chan dest hdlr]
  (go
    (loop [msg (<! chan)]
      (if (not (nil? msg))
        ;; FIXME: we are assuming :message is a string
        (let [message (:message msg)
              content (if hdlr
                        (hdlr message)
                        message)]
          ;; FIXME:  matches are exclusive, ie can't match on more than one destination
          (match [dest]
                 [{:stdout out}] (println (clojure.string/trim-newline content))
                 [{:file fpath}] (spit fpath content :append true)
                 [{:socket sock}] nil                       ;; TODO
                 [{:channel mchan}] (>! mchan content)
                 [{:in-mem data}] (.append data content)    ;; FIXME: in-mem can be an arbitrary data type
                 :else (timbre/error "Unknown destination type " dest))
          (recur (<! chan)))
        (timbre/info "Channel is closed for" (first (keys dest)))))))

(defrecord DataTap
  [data-channel                                             ;; where messages from publisher will go
   destination                                              ;; where to send processed message to
   handler
   process                                                  ;; a function that takes a channel and a destination
   ])


(defn make->DataTap
  "A DataTap subscribes to a topic from a publisher (in-channel).  These messages will be forwarded
  to the given data-channel (or a default one will be created).  A processing function will pull
  messages from the data-channel, optionally apply a handler to the message.
  "
  [publisher & {:keys [data-channel destination handler process]
                :or   {data-channel (chan 10)
                       destination  {:stdout *out*}
                       process      dispatcher}}]
  ;; Tap into the multicaster
  (async/tap publisher data-channel)
  (let [dt (->DataTap data-channel destination handler process)
        pfn (:process dt)]
    ;; create the dispatcher core.async process
    (pfn data-channel destination handler)
    dt))

(defn string-dispatcher
  "Can be used as a process function for a DataTap to save the stdout"
  [chan dest hdlr]
  (go
    (loop [msg (<! chan)]
      (when msg
        ;; FIXME: we are assuming :message is a string
        (let [content (clojure.string/trim-newline (:message msg))
              content (if hdlr
                        (hdlr content)
                        content)
              {:keys [in-mem]} dest]
          ;; Use a sliding buffer channel
          (>! in-mem content))
        (recur (<! chan))))))

(defn stdout
  [chan]
  (let [sb (StringBuilder.)]
    (go
      (loop [line (<! chan)]
        (when line
          (do
            (.append sb line)
            (recur (<! chan))))))
    (.toString sb)))

(defn make->DataTap->StringBuilder
  "This creates a specialized DataTap that consumes input from the subscription channel and stores
  to an in-memory data structure.  However, if the size of the String grows too large, any excess will
  be dropped"
  [])

(defn create-default-consumers
  "Creates DataTaps based on destinations.  By default, creates one for showing data to stdout, and
  another that saves data to a StringBuilder"
  ([publisher destinations]
   (let [datataps (for [d destinations]
                    (let [name (-> (second d) keys first)]
                      [name (apply make->DataTap publisher d)]))]
     (apply hash-map (flatten datataps))))
  ([publisher]
   (let [dest [[:destination {:stdout *out*}]
               [:destination {:file *default-log*} :data-channel (chan (async/sliding-buffer 100))]
               [:destination {:in-mem (StringBuilder.)}]]]
     (create-default-consumers publisher dest))))

;; =============================================================================
;; helpers to create different kinds of DataTaps
;; =============================================================================

;; TODO: Create a DataTap that appends to a file
(defn create-file-datatap
  [fpath]
  )


;; =============================================================================
;; A File watcher
;; Can be used to monitor
;; =============================================================================
(defn watch-file
  [fname]
  (let [watcher ]))