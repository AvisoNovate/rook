(defproject io.aviso/rook "0.1.2"
  :description "Ruby on Rails-style resource mapping for Clojure/Compojure web apps"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware Licencse 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  ;; Normally we don't AOT compile; only when tracking down reflection warnings.
  :profiles {:reflection-warnings {:aot         :all
                                   :global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [compojure "1.1.6"]
                 [org.clojure/core.memoize "0.5.6"]]
  :profiles {:dev {:dependencies [[ring-mock "0.1.5"]]}}
  :plugins [[test2junit "1.0.1"]])
