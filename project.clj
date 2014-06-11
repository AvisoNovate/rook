(defproject io.aviso/rook "0.1.10-SNAPSHOT"
  :description "Ruby on Rails-style resource mapping for Clojure/Compojure web apps"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
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
                 [ring-middleware-format "0.3.2"]
                 [prismatic/schema "0.2.2"]
                 [compojure "1.1.6"]]
  :plugins [[speclj "3.0.2"]]
  :test-paths ["spec"]
  :codox {:src-dir-uri               "https://github.com/AvisoNovate/rook/blob/master/"
          :src-linenum-anchor-prefix "L"
          :defaults                  {:doc/format :markdown}})
