(ns commando.command
  (:require [taoensso.timbre :as timbre]
            [commando.core :refer [items]]
            [clj-ssh.ssh :as sshs]
            [clj-ssh.cli :as sshc]
            [clojure.string :refer [split]]
            [commando.protos.protos :refer [Worker InfoProducer Executor Publisher]]
            [commando.monitor :as mon]
            [clojure.core.async :as async :refer [chan pub sub]])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.lang ProcessBuilder]
           [java.io File]
           (commando.command InfoProducer)
           (commando.protos.protos Publisher)
           (commando.monitor LogProducer)))

(sshc/default-session-options {:strict-host-key-checking :no})

;; NOTE: Use this function if you're working at the REPL
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


;; NOTE:  This function will not work from the REPL.  If you use this, use it from within
;; another clojure program (is there a way to tell you are executing from the repl?)
;; FIXME: This also doesn't appear to be working
(comment
  (defn ssh-p
    "Executes a command on a remote host.

    - host: hostname or IP address to execute cmd on
    - cmd: the command to execute"
    [^String host ^String cmd & {:keys [username loglvl pvtkey-path]
                                 :or   {username "root" loglvl :info pvtkey-path "~/.ssh/id_auto_dsa"}}]
    (timbre/logf loglvl "On %s| Executing command: %s" host cmd)
    (let [agent (sshs/ssh-agent {:use-system-ssh-agent true})
          session (sshs/session agent host {:strict-host-key-checking :no})]
      (sshs/with-connection session
                            (sshs/ssh session {:in cmd})))))

;; ==========================================================================================
;;
;; ==========================================================================================
(extend-type java.lang.Process
  InfoProducer
  (get-output
    [proc {:keys [data]}]
    (future
      (let [inp (-> (.getInputStream proc) InputStreamReader. BufferedReader.)
            out-chan (:out-channel data)]
        (loop [line (.readLine inp)
               running? (alive? proc)]
          ;; FIXME: abstract the println.  What if user doesn't want to print stdout or wants it to
          ;; to go to a network channel or to a core.async channel?  Make the channel which is already
          ;; a publisher, publish to println, file handler, etc.
          (cond line (do
                       (async/>!! out-chan (str line "\n"))
                       (recur (.readLine inp) (alive? proc)))
                (not running?) proc
                :else (do
                        (timbre/warn "unknown condition in get-output")
                        proc))))))

  Worker
  (alive? [this]
    (.isAlive this))

  (get-input [this]
    (let [outp (-> (.getOutputStream this) (OutputStreamWriter.))]
      outp))

  (get-status [this]
    (.exitValue this)))


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
;; Commander
;; Represents how to call a local subprocess
;; ==========================================================================================
(defrecord Commander
  [cmd                                                      ;; vector of String
   ^File work-dir                                           ;; working directory
   env                                                      ;; environment map to be used by process
   ^Boolean combine-err?                                    ;; redirect stderr to stdout?
   ^Boolean block?
   output                                                   ;; channel to send process output to
   log-consumers                                            ;; seq of LogProducers
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
        @(get-output proc {:data logger})
        (do
          (get-output proc {:data logger})
          proc))))

  ;; FIXME: This will no longer work now
  (output [cmdr]
    (.toString (get-channel cmdr)))

  Publisher
  (topics [this]
    @(:topics (:logger this)))
  (publish-to [this topic out-chan]
    (let [logprod (:logger this)
          to-chan (:out-channel logprod)]
      (swap! (:topics logprod) conj topic)
      (sub to-chan topic out-chan))))

;; ==========================================================================================
;; Commander related functions
;; ==========================================================================================
(defn get-channel
  [cmdr]
  (:out-channel (:logger cmdr)))

(defn subscribe
  "Given a Commander object, subscribe to a given topic"
  [cmdr topic out-chan]
  (publish-to cmdr topic out-chan))

(defn make->Commander
  "Creates a Commander object"
  [cmd & {:keys [work-dir env combine-err? block? logger result-handler watch-handler log-consumers topics]
          :or   {combine-err?   true
                 block?         true
                 logger         (mon/make->LogProducer)
                 result-handler (fn [res]
                                  (= 0 (:out res)))
                 log-consumers ()
                 topics [:stdout]}
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
                                          :watch-handler  watch-handler}))]
    (doseq [topic topics]
      (subscribe cmdr topic ))))

;; ==========================================================================================
;; SSHProcess
;; Represents the execution of a SSHCommand
;; ==========================================================================================
(defrecord SSHProcess
  [channel
   out-stream
   err-stream
   session]
  Worker
  (alive? [this]
    (let [chan (:channel this)]
      (= (.getExitStatus chan) -1)))

  (get-status [this]
    (let [chan (:channel this)]
      (.getExitStatus chan)))

  InfoProducer
  (get-output [this {:keys [data]}]
    (let [chan (:channel this)
          os (-> (.getInputStream chan) InputStreamReader. BufferedReader.)]
      (if (not (.isConnected chan))
        (.connect chan))
      (println "connected? " (.isConnected chan))
      (loop [status (alive? this)]
        (if status
          ;; While the channel is still open, read the stdout that was piped to the InputStream
          (let [line (.readLine os)]
            (println line)
            (when data
              (.append data (str line "\n")))
            (recur (alive? this)))
          ;; There might be info in the BufferedReader once the channel closes, so read it
          (do
            (while (.ready os)
              (let [line (.readLine os)]
                (println line)
                (.append data (str line "\n"))))
            (println "Finished with status: " (.getExitStatus chan))
            this))))))


(defn make->SSHProcess
  [ssh-res]
  (map->SSHProcess ssh-res))


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
      (future (get-output ssh-res {:data logger}))))
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
    [command (call command)]))


(defn which
  "Determines if a program is in PATH and if so, returns the path if it exists or nil"
  [program & {:keys [host]}]
  (let [[cmd proc] (launch (str "which " program) :host host)
        proc (if (future? proc) @proc proc)]
    (if (= 0 (get-status proc))
      (clojure.string/trim (output cmd))
      nil)))

;(launch+ "ssh-add")
