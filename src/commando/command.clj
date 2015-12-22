(ns commando.command
  (:require [taoensso.timbre :as timbre]
            [commando.core :refer [items]]
            [clj-ssh.cli :as sshc]
            [clojure.string :refer [split]]
            [commando.protos.protos :as protos :refer [Worker InfoProducer Executor Publisher Multicaster]]
            [commando.monitor :as mon]
            [clojure.core.async :as async :refer [go chan pub sub]]
            [commando.logging :refer [get-code]]
            [clojure.core.match :refer [match]])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.lang ProcessBuilder]
           [java.io File]
           ))

(sshc/default-session-options {:strict-host-key-checking :no})


(defn ssh
  "The base function used for remote calls.

  *Args*
  - host: String of the hostname or IP of remote machine
  - cmd: String of the command to run on remote machine
  - username: (opt) String of the user to run as (defaults as root)
  - loglvl: (opt keyword) logging level for command

  *Return*
  "
  [host cmd & {:keys [username loglvl]
               :as opts
               :or {username "root" loglvl :info}}]
  (timbre/logf loglvl "On %s| Executing command: %s" host cmd)
  (let [opts (merge opts {:username username})
        args (->> (dissoc opts :loglvl) (items) (concat [host cmd]))]
    ;(timbre/info args)
    (apply sshc/ssh args)))

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
                                 (println "Process finished")
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
    (let [environ (.environment pb)]
      (doseq [[k v] env]
        (.put environ k v))))
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
  (= 0 (:status res)))

(defn throw-wrapper
  [handler throws?]
  (fn [result]
    (let [passed? (handler result)]
      (match [[passed? throws?]]
             [[true _]] result
             [[false false]] result
             [[false true]] (throw (Exception. "Failed result handler"))))))

;; ==========================================================================================
;; Commander
;; Represents how to call a local subprocess
;; ==========================================================================================
(defrecord Commander
  [cmd                                                      ;; vector of String
   ^File work-dir                                           ;; working directory
   env                                                      ;; environment map to be used by process
   ^Boolean combine-err?                                    ;; redirect stderr to stdout?
   ^Boolean block?                                          ;; if true, wait until command completes
   logger                                                   ;; a DataStore
   data-consumers                                           ;; a map of DataTaps which can work on Process messages
   result-handler                                           ;; fn predicate to determine success
   watch-handler                                            ;; function to pass to a DataTap
   ^Boolean throws?                                         ;; if true, throw an exception if result-handler is false
   ]
  Executor
  (call [cmdr]
    (let [pb (ProcessBuilder. (:cmd cmdr))
          build (comp #(combine-err! % (:combine-err? cmdr))
                      #(set-env! % (:env cmdr))
                      set-dir!)
          _ (build pb (:work-dir cmdr))
          logger (get-channel cmdr)
          proc (.start pb)
          get-results (fn []
                        (let [p (protos/get-output proc {:data logger})
                              results {:executor cmdr
                                       :process p
                                       :status  (protos/get-status p)
                                       :output  (protos/output cmdr)}
                              hdlr (throw-wrapper (:result-handler cmdr) (:throws? cmdr))]
                          (hdlr results)))]
      (if (:block? cmdr)
        (get-results)
        (future (get-results)))))

  (output [cmdr]
    (protos/get-data (-> (:data-consumers cmdr) :in-mem)))

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
  "Creates a Commander object. See Commander defrecord for details on optional keys

  The logger is a DataStore that the ssh process output will go to.  The data-consumers are DataTaps that will
  receive messages from the DataStore's multicaster channel."
  [cmd & {:keys [work-dir env combine-err? block? logger result-handler watch-handler data-consumers topics throws?]
          :or   {combine-err?   true
                 block?         true
                 logger         (mon/make->DataBus)         ;;(mon/make->DataStore)
                 result-handler default-res-hdler
                 topics         [:stdout]
                 throws?        false}
          :as   opts}]
  (let [logc (if data-consumers
               data-consumers
               (commando.monitor/create-default-consumers (:multicaster logger)))
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
                                          :data-consumers logc
                                          :throws?        throws?}))]
    cmdr))

(defn get-output-ssh
  "Function to retrieve data from an SSHProcess and send to a DataSource

   The SSHProcess will take a line of data from the output of it's child process and then put it into the
   data-channel of the DataSource.  If there is no more data in the outputstream of the child process and
   the child process is done, the data-channel will be closed.  Otherwise, it will loop and grab lines
   from the outputstream and put them into the data-channel."
  [this {:keys [data]}]
  (try
    (let [ssh-chan (:channel this)
          os (-> (:out-stream this) InputStreamReader. BufferedReader.)
          err (-> (:err-stream this) InputStreamReader. BufferedReader.)
          out-chan (:data-channel data)
          is-done? #(= (.getExitStatus ssh-chan) -1)
          finish #(let [status (.getExitStatus ssh-chan)]
                   ;(async/>!! out-chan {:topic :stdout :message (format "Finished with status: " status)})
                   (loop [out? (.ready os)
                          error? (.ready err)]
                     (let [ready? (or out? error?)
                           o (when out? (.readLine os))
                           e (when error? (.readLine err))]
                       (when ready?
                         (doseq [line [o e] :when (not (nil? line))]
                           (async/>!! out-chan {:topic :stdout :message (str line "\n")}))
                         (recur (.ready os) (.ready err)))))
                   (async/close! out-chan)
                   this)]
      (when-not (.isConnected ssh-chan)
        (println "connecting to ssh channel")
        (.connect ssh-chan))
      ;; While the buffer has data read from the outputstream and shove it into the the data logger channel
      (loop [os? (.ready os)
             err? (.ready err)
             running? (is-done?)]
        (let [ready? (or os? err?)]
          (match [[ready? running?]]
                 ;; If process is done, and there's nothing in buffer, we're done
                 [[false false]] (finish)
                 [[false nil]] (do (println "got nil for running? ") (finish))
                 ;; if process is still running, but buffer has no data, keep going
                 [[false true]] (recur (.ready os) (.ready err) (is-done?))
                 ;; if we've got data in the buffer, we dont care if process is done or not.  grab the data
                 [[true _]] (let [lines (match [os? err?]
                                               [true false] [(.readLine os)]
                                               [true true] [(.readLine os) (.readLine err)]
                                               [false false] []
                                               [false true] [(.readLine err)])]
                              (doseq [line lines]
                                (async/>!! out-chan {:topic :stdout :message (str line "\n")}))
                              (recur (.ready os) (.ready err) (is-done?)))))))
    (catch Exception ex
      (-> (:data-channel data) async/close!))))

;(def get-output-ssh (make-output-fn :out-stream))
;(def get-error-ssh (make-output-fn :err-stream))

;; ==========================================================================================
;; SSHProcess
;; Represents the execution of a SSHCommand.
;; ==========================================================================================
(defrecord SSHProcess
  [channel                                                  ;; SSH channel
   out-stream                                               ;;
   err-stream
   session
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

  ;; Ughhh, clj-ssh does not combine stdout and stderr.  So we create another thread for
  ;; output and one for error.  Each will pump data to the same DataBus, so the DataTap(s)
  ;; connected to it will get from both stdout and stderr.  However, it is possible that
  ;; one thread will run faster than the other, so the ordering of stdout and stderr may
  ;; not be correct
  InfoProducer
  (get-output [this {:keys [data]}]
    (get-output-ssh this {:data data})))


(defn make->SSHProcess
  "Constructor for an SSHProcess"
  [ssh-res]
  ;; TODO:  need to create
  (map->SSHProcess ssh-res))


;; ==========================================================================================
;; SSHCommander represents how to execute remote processes
;; ==========================================================================================
(defrecord SSHCommander
  [^String host                                             ;; The hostname or IP address of remote machine
   ^String cmd                                              ;; The command to run
   logger                                                   ;; A DataStore that the SSHProcess will send data to
   data-consumers                                           ;; A sequence of DataTaps
   result-handler                                           ;; How to determine pass/fail
   topics
   block?                                                   ;; If true, block until command completes
   throws?                                                  ;; If true, throw exception on failure
   env
   work-dir                                                 ;; Working directory to issue command
   ]

  Executor
  (call [this]
    (let [host (:host this)
          cmd (:cmd this)
          logger (:logger this)
          ssh-res (make->SSHProcess (ssh host cmd :out :stream))
          block? (:block? this)
          get-results (fn []
                        (let [p (protos/get-output ssh-res {:data logger})
                              results {:executor this
                                       :process p
                                       :status  (protos/get-status p)
                                       :output  (protos/output this)}
                              hdlr (throw-wrapper (:result-handler this) (:throws? this))]
                          (hdlr results)))]
      (if block?
        (get-results)
        (future (get-results)))))

  (output [this]
    (protos/get-data (-> (:data-consumers this) :in-mem))))


(defn make->SSHCommander
  "Creates a new SSHCommander object

  The logger is a DataStore that the ssh process output will go to.  The data-consumers are DataTaps that will
  receive messages from the DataStore's multicaster channel."
  [host cmd & {:keys [logger data-consumers result-handler topics block? env work-dir throws?]
               :or {logger (mon/make->DataBus)              ;;(mon/make->DataStore)
                    result-handler default-res-hdler
                    topics [:stdout]
                    block? true
                    throws? false}
               :as opts}]
  (let [logc (if data-consumers
               data-consumers
               (commando.monitor/create-default-consumers (:multicaster logger)))
        cmd+ (if work-dir
               (str (format "cd %s;" work-dir) cmd)
               cmd)
        cmd+ (if env
               (str (clojure.string/join ";" (for [[k v] env]
                                               (format "export %s=%s" k v))) ";" cmd+)
               cmd+)
        m {:host host :cmd cmd+ :logger logger :data-consumers logc :result-handler result-handler
           :topics topics :block? block? :env env :work-dir work-dir :throws? throws?}]
    (timbre/debug m)
    (map->SSHCommander m)))


(defn reducer [m]
  "flattens a map (one-level) by turning it into a sequence of (k1 v1 k2 v2 ..)"
  (reduce #(concat %1 %2) [] (for [[k v] m]
                               [k v])))


(defn launch
  "Factory to create either a Commander or SSHCommander object and run it's call method.

  Usage:
  ```
      (launch \"iostat 2 5\" :block? false)
      (launch \"iostat 2 5\")
      (launch \"ls -al\" :host \"my-machine.foo.com\")
      (launch \"ls -al /foo\" :throws? true)  => throws an exception
  ```
  "
  [cmd & {:keys [host]
          :as opts}]
  (let [command (if host
                  (apply make->SSHCommander host cmd (reducer opts))
                  (apply make->Commander cmd (reducer opts)))]
    (timbre/debug "opts for launch are: " opts)
    (protos/call command)))

(defn which
  "Determines if a program is in PATH and if so, returns the path if it exists or nil"
  [program & {:keys [host]}]
  (let [{:keys [executor process]} (launch (str "which " program) :host host)
        process (if (future? process) @process process)]
    (if (= 0 (protos/get-status process))
      (clojure.string/trim (protos/output executor))
      nil)))


(launch "ssh-add")
