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
  (read-info [this]))
