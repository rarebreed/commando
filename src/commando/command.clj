(ns commando.command
  (:require [taoensso.timbre :as timbre]
            [commando.core :refer [items]]
            [clj-ssh.cli :as sshc]
            [clojure.string :refer [split]]
            [commando.protos.protos :as protos :refer [Worker InfoProducer Executor Publisher Multicaster]]
            [commando.monitor :as mon]
            [clojure.core.async :as async :refer [chan pub sub]]
            [commando.logging :refer [get-code]]
            [clojure.core.match :refer [match]])
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
    (let [inp (-> (.getInputStream proc) InputStreamReader. BufferedReader.)
          out-chan data]
      (loop [ready? (.ready inp)
             running? (.isAlive proc)]
        (match [[ready? running?]]
               ;; If the process isn't running and there's nothing in inp, we're done
               [[false false]] (do
                                 (async/close! out-chan)
                                 proc)
               ;; Process is still going, but nothing is available in buffer: keep going
               [[false true]] (recur (.ready inp) (.isAlive proc))
               ;; If there's something in buffer, we don't care if process is alive or not.  grab data
               [[true _]] (let [line (.readLine inp)]
                            (async/>!! out-chan {:topic :stdout :message (str line "\n")})
                            (recur (.ready inp) (.isAlive proc))))))))


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
   data-consumers                                           ;; a seq of DataTaps which can work on Process messages
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
        (protos/get-output proc {:data logger})
        (future (protos/get-output proc {:data logger})))))

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
      (sub publisher topic out-chan)))

  Multicaster
  (listeners [this]
    (:data-consumers this))

  (tap-into [this to-chan]
    (let [multicaster (:logger this)]
      (async/tap multicaster to-chan)))

  (untap-from [this from-chan]
    (let [multicaster (:logger this)]
      (async/untap multicaster from-chan))))


(defn make->Commander
  "Creates a Commander object

  The Commander "
  [cmd & {:keys [work-dir env combine-err? block? logger result-handler watch-handler data-consumers topics]
          :or   {combine-err?   true
                 block?         true
                 logger         (mon/make->DataBus)         ;;(mon/make->DataStore)
                 result-handler default-res-hdler
                 topics         [:stdout]}
          :as   opts}]
  (let [logc (if data-consumers
               data-consumers
               ;(commando.monitor/create-default-consumers (:multicaster logger))
               [(commando.monitor/make->DataTap (:multicaster logger))
                (commando.monitor/make->DataTap (:multicaster logger)
                                                :destination {:in-mem (StringBuilder.)})
                (commando.monitor/make->DataTap (:multicaster logger)
                                                :destination {:file "/tmp/commando.log"}
                                                :data-channel (chan (async/sliding-buffer 100)))]
               )
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
                                          :data-consumers   logc}))]
    cmdr))


(defn get-output-ssh
  [this {:keys [data]}]
  (let [chan (:channel this)
        os (-> (.getInputStream chan) InputStreamReader. BufferedReader.)
        out-chan (:data-channel data)
        is-done? #(= (.getExitStatus chan) -1)
        finish #(let [status (.getExitStatus chan)]
                 (async/>!! out-chan {:topic :stdout :message (format "Finished with status: " status)})
                 (async/close! out-chan)
                 this)]
    (if (not (.isConnected chan))
      (.connect chan))
    (timbre/info "connected? " (.isConnected chan))
    ;; While the buffer has data read from the outputstream and shove it into the the data logger channel
    (loop [ready? (.ready os)
           running? (is-done?)]
      (match [[ready? running?]]
             ;; If process is done, and there's nothing in buffer, we're done
             [[false false]] (finish)
             [[false nil]] (finish)
             ;; if process is still running, but buffer has no data, keep going
             [[false true]] (recur (.ready os) (is-done?))
             ;; if we've got data in the buffer, we dont care if process is done or not.  grab the data
             [[true _]] (let [line (.readLine os)]
                          (when line
                            (async/>!! out-chan {:topic :stdout :message (str line "\n")}))
                          (recur (.ready os) (is-done?)))))))

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
    (get-output-ssh this {:data data})))


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
   data-consumers                                            ;; A sequence of DataTaps
   result-handler                                           ;; How to determine pass/fail
   topics
   block?
   ;; TODO: add working directory and environment variables
   env
   work-dir]
  Executor
  (call [this]
    (let [host (:host this)
          cmd (:cmd this)
          logger (:logger this)
          ssh-res (make->SSHProcess (ssh host cmd :out :stream))
          block? (:block? this)]
      (print "block is " block?)
      (if block?
        (protos/get-output ssh-res {:data logger})
        (future (protos/get-output ssh-res {:data logger})))))

  (output [this]
    (.toString (:logger this))))


(defn make->SSHCommander
  "Creates a new SSHCommander object

  The logger is a DataStore that the ssh process output will go to.  The data-consumers are DataTaps that will
  receive messages from the DataStore's multicaster channel."
  [host cmd & {:keys [logger data-consumers result-handler topics block? env work-dir]
               :or {logger (mon/make->DataBus)              ;;(mon/make->DataStore)
                    result-handler default-res-hdler
                    topics [:stdout]
                    block? true}
               :as opts}]
  (let [logc (if data-consumers
               data-consumers
               (commando.monitor/create-default-consumers (:multicaster logger)))
        cmd+ (if work-dir
               (str (format "cd %s;" work-dir) cmd)
               cmd)
        m {:host host :cmd cmd+ :logger logger :data-consumers logc :result-handler result-handler
           :topics topics :block? block? :env env :work-dir work-dir}]
    (timbre/info m)
    (map->SSHCommander m)))


(defn reducer [m]
  "flattens a map (one-level) by turning it into a sequence of (k1 v1 k2 v2 ..)"
  (reduce #(concat %1 %2) [] (for [[k v] m]
                               [k v])))


(defn launch
  "Improved way to launch a command"
  [cmd & {:keys [host]
          :as opts}]
  (let [command (if host
                  (apply make->SSHCommander host cmd (reducer opts))
                  (apply make->Commander cmd (reducer opts)))]
    (timbre/info "opts for launch are: " opts)
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
