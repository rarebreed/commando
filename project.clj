(defproject commando "0.1.2-SNAPSHOT"
  :description "A set of libraries to run subprocesses either locally or remotely via ssh"
  :url "https://github.com/rarebreed/commando"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-ssh "0.5.14"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/core.async "0.2.371"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [clj-time "0.11.0"]]

  :repl-options {:init-ns commando.command}
  )
