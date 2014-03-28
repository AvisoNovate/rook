(defproject io.aviso/rook "0.1.8-SNAPSHOT"
  :description "Ruby on Rails-style resource mapping for Clojure/Compojure web apps"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  ;; Normally we don't AOT compile; only when tracking down reflection warnings.
  :profiles {:reflection-warnings {:aot         :all
                                   :global-vars {*warn-on-reflection* true}}
             :dev {:dependencies [[ring-mock "0.1.5"]
                                  [io.aviso/pretty "0.1.10"]
                                  [speclj "2.9.1"]
                                  [log4j "1.2.17"]]}}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring-middleware-format "0.3.2"]
                 [prismatic/schema "0.2.1"]
                 [compojure "1.1.6"]]
  :plugins [[test2junit "1.0.1"]
            [speclj "2.9.1"]]
  :test-paths ["spec"])
