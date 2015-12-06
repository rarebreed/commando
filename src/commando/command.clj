(ns commando.command
  (:require [taoensso.timbre :as timbre]
            [commando.core :refer [items]]
            [clj-ssh.cli :as sshc]
            [clojure.string :refer [split]]
            [commando.protos.protos :as protos :refer [Worker InfoProducer Executor Publisher]]
            [commando.monitor :as mon]
            [clojure.core.async :as async :refer [chan pub sub]]
            [commando.logging :refer [get-code]])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.lang ProcessBuilder]
           [java.io File]
           ))

(sshc/default-session-options {:strict-host-key-checking :no})


(defn ssh
  ""
  [host cmd & {:keys [username loglvl]
               :as opts
               :or {username "root" loglvl :info}}]
  (timbre/logf loglvl "On %s| Executing command: %s" host cmd)
  (let [opts (merge opts {:username username})
        args (->> (dissoc opts :loglvl) (items) (concat [host cmd]))]
    (timbre/info args)
    (apply sshc/ssh args)))

;; ==========================================================================================
;; Implementation of InfoProducer on a java.io.File
;; ==========================================================================================
(extend-type java.io.File
  InfoProducer
  (get-output [this {:keys [data]}]
    ))


;; ==========================================================================================
;; Implementation of a Worker and InfoProducer on a java.lang.Process
;; ==========================================================================================
(extend-type java.lang.Process
  Worker
  (alive? [this]
    (.isAlive this))

  (get-data-sink [this]
    (let [outp (-> (.getOutputStream this) (OutputStreamWriter.))]
      outp))

  (get-status [this]
    (.exitValue this))

  InfoProducer
  (get-output [proc {:keys [data]}]
    (future
      (let [inp (-> (.getInputStream proc) InputStreamReader. BufferedReader.)
            out-chan data]
        (loop [line (.readLine inp)
               running? (.isAlive proc)]
          ;; send the message to stdout topic and let the DataTap consumer decide what to do with it
          ;; TODO: add a fan out (make it a mult channel) so we can send to
          (cond line (do
                       (async/>!! out-chan {:topic :stdout :message (str line "\n")})
                       (recur (.readLine inp) (.isAlive proc)))
                (not running?) proc
                :else (do
                        (timbre/warn "unknown condition in get-output")
                        proc)))))))


;; ==========================================================================================
;; Helper functions for ProcessBuilder
;; ==========================================================================================

(defn set-dir!
  [pb dir]
  {:pre [#(if dir (.exists dir) true)]}
  (when dir
    (.directory pb dir))
  pb)

(defn set-env!
  [pb env]
  (when env
    (.environment pb env))
  pb)

(defn combine-err!
  [pb combine?]
  (.redirectErrorStream pb combine?)
  pb)


;; ==========================================================================================
;; Commander related functions
;; ==========================================================================================
(defn get-channel
  [cmdr]
  (:data-channel (:logger cmdr)))


(defn default-res-hdler [res]
  (= 0 (:out res)))


;; ==========================================================================================
;; Commander
;; Represents how to call a local subprocess
;; ==========================================================================================
(defrecord Commander
  [cmd                                                      ;; vector of String
   ^File work-dir                                           ;; working directory
   env                                                      ;; environment map to be used by process
   ^Boolean combine-err?                                    ;; redirect stderr to stdout?
   ^Boolean block?
   logger                                                   ;; a DataStore
   log-consumer                                             ;; seq of DataTaps which can work on Process messages
   result-handler                                           ;; fn to determine success
   watch-handler                                            ;; function to pass to a DataTap
   ]
  Executor
  (call [cmdr]
    (let [pb (ProcessBuilder. (:cmd cmdr))
          build (comp #(combine-err! % (:combine-err? cmdr))
                      #(set-env! % (:env cmdr))
                      set-dir!)
          _ (build pb (:work-dir cmdr))
          logger (get-channel cmdr)
          proc (.start pb)]
      (if (:block? cmdr)
        @(protos/get-output proc {:data logger})
        (do
          (protos/get-output proc {:data logger})
          proc))))

  ;; FIXME: This will no longer work now
  (output [cmdr]
    (.toString (get-channel cmdr)))

  Publisher
  (topics [this]
    @(:topics (:logger this)))
  (publish-to [this topic out-chan]
    (let [logprod (:logger this)
          publisher (:publisher logprod)]
      (swap! (:topics logprod) conj topic)
      (sub publisher topic out-chan))))


(defn make->Commander
  "Creates a Commander object

  The Commander "
  [cmd & {:keys [work-dir env combine-err? block? logger result-handler watch-handler log-consumer topics]
          :or   {combine-err?   true
                 block?         true
                 logger         (mon/make->DataStore)
                 result-handler default-res-hdler
                 topics         [:stdout]}
          :as   opts}]
  (let [logc (if log-consumer
               log-consumer
               (commando.monitor/make->DataTap (:publisher logger)))
        cmdr (map->Commander (merge opts {:cmd            (if (= String (class cmd))
                                                            (split cmd #"\s+")
                                                            cmd)
                                          :work-dir       (when work-dir
                                                            (File. work-dir))
                                          :env            env
                                          :combine-err?   combine-err?
                                          :block?         block?
                                          :logger         logger
                                          :result-handler result-handler
                                          :watch-handler  watch-handler
                                          :log-consumer   logc}))]
    ;; TODO: subscribe the topics
    cmdr))

;; ==========================================================================================
;; SSHProcess
;; Represents the execution of a SSHCommand
;; ==========================================================================================
(defrecord SSHProcess
  [channel
   out-stream
   err-stream
   session
   ;; Need to add LogProducer and LogConsumers
   ]
  Worker
  (alive? [this]
    (let [chan (:channel this)]
      (= (.getExitStatus chan) -1)))

  (get-status [this]
    (let [chan (:channel this)]
      (.getExitStatus chan)))

  (get-data-sink [this]
    (let [chan (:channel this)]
      (-> (.getOutputStream chan) OutputStreamWriter.)))

  InfoProducer
  (get-output [this {:keys [data]}]
    (let [chan (:channel this)
          os (-> (.getInputStream chan) InputStreamReader. BufferedReader.)
          ;; TODO: need to add a core.async channel here
          out-chan data]
      (if (not (.isConnected chan))
        (.connect chan))
      (timbre/info "connected? " (.isConnected chan))
      ;; While the ssh child process is active, read from the outputstream and shove it into the
      ;; the data logger channel
      (loop [running? (= (.getExitStatus chan) -1)]
        (when running?
          ;; While the channel is still open, read the stdout that was piped to the InputStream
          (let [line (.readLine os)]
            (cond line (do
                         (async/>!! out-chan {:topic :stdout :message (str line "\n")})
                         (recur (= (.getExitStatus chan) -1)))
                  (not running?) (timbre/info "Process exited")
                  :else (timbre/warn "Unknown condition")))))
      ;; There might be info in the BufferedReader once the channel closes, so read it
      (while (.ready os)
        (let [line (.readLine os)]
          ;; FIXME: use LogProducer/Publisher instead
          (when line
            (async/>!! out-chan {:topic :stdout :message (str line "\n")}))))
      (println "Finished with status: " (.getExitStatus chan))
      this)))


(defn make->SSHProcess
  "Constructor for an SSHProcess"
  [ssh-res]
  ;; TODO:  need to create
  (map->SSHProcess ssh-res))


;; ==========================================================================================
;;
;; ==========================================================================================
(defrecord SSHCommander
  [^String host                                             ;; The hostname or IP address of remote machine
   ^String cmd                                              ;; The command to run
   logger                                                   ;; A DataStore that the SSHProcess will send data to
   log-consumer                                             ;; A DataTap
   result-handler                                           ;; How to determine pass/fail
   topics
   ;; TODO: add working directory and environment variables
   env
   work-dir]
  Executor
  (call [this]
    (let [host (:host this)
          cmd (:cmd this)
          logger (:logger this)
          ssh-res (make->SSHProcess (ssh host cmd :out :stream))]
      (future (protos/get-output ssh-res {:data logger}))))
  (output [this]
    (.toString (:logger this))))


(defn make->SSHCommander
  "Creates a new SSHCommander object

  The logger is a DataStore that the ssh process output will go to.  The log-consumer is a DataTap that will
  pull messages from the DataStore's publisher channel.  The topics sequence determines what messages it will
  retrieve from the DataStore's publisher channel (because those are the topics it will subscribe to)"
  [host cmd & {:keys [logger log-consumer result-handler topics env work-dir]
               :or {logger (mon/make->DataStore)
                    result-handler default-res-hdler
                    topics [:stdout]}
               :as opts}]
  ;; TODO: do we create a DataTap log-consumer per topic?  or do we just subscribe to multiple topics?
  (let [logc (if log-consumer
               log-consumer
               (commando.monitor/make->DataTap (:publisher logger)))
        m {:host host :cmd cmd :logger logger :log-consumer logc :result-handler result-handler
           :topics topics :env env :work-dir work-dir}]
    (println m)
    (map->SSHCommander m)))


(defn reducer [m]
  "flattens a map (one-level) by turning it into a sequence of (k1 v1 k2 v2 ..)"
  (reduce #(concat %1 %2) []
          (for [[k v] m]
            [k v])))


(defn launch
  "Improved way to launch a command"
  [cmd & {:keys [host]
          :as opts}]
  (let [command (if host
                  (make->SSHCommander host cmd)
                  (apply make->Commander cmd (reducer opts)))]
    [command (protos/call command)]))

(comment
  (defn which
    "Determines if a program is in PATH and if so, returns the path if it exists or nil"
    [program & {:keys [host]}]
    (let [[cmd proc] (launch (str "which " program) :host host)
          proc (if (future? proc) @proc proc)]
      (if (= 0 (get-status proc))
        (clojure.string/trim (output cmd))
        nil))))

;(launch+ "ssh-add")

(def code (get-code))
