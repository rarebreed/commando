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
   output                                                   ;; a
   log-consumer                                             ;; seq of DataTaps which can work on Process messages
   result-handler                                           ;; fn to determine success
   watch-handler                                            ;; function launched in a separate thread
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
                 logger         (mon/make->DataSource)
                 result-handler (fn [res]
                                  (= 0 (:out res)))
                 topics         [:stdout]}
          :as   opts}]
  (let [cmdr (map->Commander (merge opts {:cmd            (if (= String (class cmd))
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
                                          :log-consumer   (commando.monitor/make->DataTap (:publisher logger))}))]
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
          ]
      (if (not (.isConnected chan))
        (.connect chan))
      (println "connected? " (.isConnected chan))
      (loop [status (= (.getExitStatus chan) -1)]
        (if status
          ;; While the channel is still open, read the stdout that was piped to the InputStream
          (let [line (.readLine os)]
            ;; FIXME:  Use LogProducer/Publisher instead
            (println line)
            (when data
              (.append data (str line "\n")))
            (recur (= (.getExitStatus chan) -1)))
          ;; There might be info in the BufferedReader once the channel closes, so read it
          (do
            (while (.ready os)
              (let [line (.readLine os)]
                ;; FIXME: use LogProducer/Publisher instead
                (println line)
                (.append data (str line "\n"))))
            (println "Finished with status: " (.getExitStatus chan))
            this))))))


(defn make->SSHProcess
  "Constructor for an SSHProcess"
  [ssh-res]
  ;; TODO:  need to create
  (map->SSHProcess ssh-res))


;; ==========================================================================================
;;
;; ==========================================================================================
(defrecord SSHCommander
  [^String host
   ^String cmd
   ^StringBuilder logged!
   result-handler
   watch-handler
   ;; TODO: what else do we need?
   ]
  Executor
  (call [this]
    (let [host (:host this)
          cmd (:cmd this)
          logger (:logged! this)
          ssh-res (make->SSHProcess (ssh host cmd :out :stream))]
      (future (protos/get-output ssh-res {:data logger}))))
  (output [this]
    (.toString (:logged! this))))


(defn make->SSHCommander
  [host cmd & {:keys [logger result-handler watch-handler]
               :or {logger (StringBuilder. 1024)}
               :as opts}]
  (let [m {:host host :cmd cmd :logger logger :result-handler result-handler :watch-handler watch-handler}]
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
