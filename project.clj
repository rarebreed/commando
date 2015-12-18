(defproject commando "0.1.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-ssh "0.5.11"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/core.async "0.2.371"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [clj-time "0.11.0"]]

  :repl-options {:init-ns commando.command}
  )
