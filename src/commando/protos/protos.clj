(ns commando.protos.protos)


;; ==========================================================================================
;; An InfoProducer producer produces data of some format.  This could be a process's stdout
;; or even a file which is being written to
;; ==========================================================================================
(defprotocol InfoProducer
  (get-output [this {:keys [data]}] "Get information from the producer in real-time, send to data channel"))

;; ==========================================================================================
;; The Executor Protocol
;; This represents how to execute a command either locally or remotely.  Remote calls could be
;; done via SSH, uplift.messaging, or even (potentially) a REST call
;; ==========================================================================================
(defprotocol Executor
  "Any object that supports execution of a system command should implement this"
  (call [this] "Execute the process")
  (output [this] "Get saved output of the process"))


(defprotocol Publisher
  (topics [this] "Returns a list of topics")
  (publish-to [this topic out-chan] "Adds a topic to an outbound channel"))

(defn subscribe
  "Given a Publisher object, subscribe to a given topic"
  [cmdr topic out-chan]
  (publish-to cmdr topic out-chan))

;; ==========================================================================================
;; Worker
;; Where Executor encapsulates behavior of _how_ to execute a process, Worker describes
;; _what_ can be done with the process
;; ==========================================================================================
(defprotocol Worker
  "API for a unit of work"
  (alive? [this] "Is the unit of work still in progress (get it's state)")
  (send-input [this] "Send information to the process in real time")
  (get-data-sink [this] "Get the entity from which to retrieve data")
  ;(get-error [this] "Get any error information from the process")
  (get-status [this] "The status of a process"))

(defprotocol InfoReader
  (get-data [this]))

(defprotocol Multicaster
  (listeners [this] "Returns who is listening")
  (tap-into [this to-chan] "Allows a consumer to hook into a message bus")
  (untap-from [this from-chan] "Untaps a consumer from the message bus"))

;; =====================================================================================
;; get-data takes a DataTap, and determines the destination type
;; based on destination type, it can retrieve data from the DataTap
;; =====================================================================================
(defmulti get-data
          "Retrieve data from a DataTap based on destination type.  Note that
          calling this function will block until the channel is closed (returns nil)"
          (fn [this]
            (let [dtype (-> (:destination this) keys first)]
              dtype)))

(defmethod get-data :in-mem
  [this]
  (while (not @(:closed? this)))
  (let [sb (-> (:destination this) :in-mem)]
    (.toString sb)))

(defmethod get-data :file
  [this]
  (let [fpath (-> (:destination this) :file)]
    (slurp fpath)))

;; ==========================================================================================
;; Implementation of InfoProducer on a java.io.File
;; ==========================================================================================
(extend-type java.io.File
  InfoProducer
  (get-output [this {:keys [data]}]
    (.read this)))
