(defproject io.aviso/rook "0.1.9-SNAPSHOT"
  :description "Ruby on Rails-style resource mapping for Clojure/Compojure web apps"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  ;; Normally we don't AOT compile; only when tracking down reflection warnings.
  :profiles {:dev
              {:dependencies [[ring-mock "0.1.5"]
                              [io.aviso/pretty "0.1.11"]
                              [clj-http "0.9.1"]
                              [speclj "3.0.2"]
                              [log4j "1.2.17"]]}}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring "1.2.2"]
                 [ring-middleware-format "0.3.2" :exclusions [cheshire
                                                              org.clojure/tools.reader]]
                 [prismatic/schema "0.2.1" :exclusions [potemkin]]
                 [compojure "1.1.6"]]
  :plugins [[speclj "3.0.2"]]
  :test-paths ["spec"])
