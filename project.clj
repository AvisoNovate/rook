(defproject io.aviso/rook "0.1.12-SNAPSHOT"
  :description "Sane, smart, fast, Clojure web services"
  :url "http://howardlewisship.com/io.aviso/documentation/rook"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev
              {:dependencies [[ring-mock "0.1.5"]
                              [io.aviso/pretty "0.1.11"]
                              [clj-http "0.9.1" :exclusions [cheshire]]
                              [speclj "3.0.2"]
                              [log4j "1.2.17"]]}}
  ;; List "resolved" dependencies first, which occur when there are conflicts.
  ;; We pin down the version we want, then exclude anyone who disagrees.
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.3.1"]
                 [potemkin "0.3.4"]
                 [org.clojure/tools.reader "0.8.5"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]
                 [org.clojure/tools.logging "0.3.0"]
                 [io.aviso/tracker "0.1.0"]
                 [ring "1.3.0" :exclusions [org.clojure/tools.reader]]
                 [medley "0.5.0" :exclusions [com.keminglabs/cljx org.clojure/clojure]]
                 [ring-middleware-format "0.3.2" :excludes [cheshire]]
                 [prismatic/schema "0.2.4" :exclusions [potemkin]]]
  :plugins [[speclj "3.0.2"]]
  :test-paths ["spec"]
  :codox {:src-dir-uri               "https://github.com/AvisoNovate/rook/blob/master/"
          :src-linenum-anchor-prefix "L"
          :defaults                  {:doc/format :markdown}})
