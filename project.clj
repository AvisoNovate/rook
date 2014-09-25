(defproject io.aviso/rook "0.1.15-SNAPSHOT"
  :description "Sane, smart, fast, Clojure web services"
  :url "http://howardlewisship.com/io.aviso/documentation/rook"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev
              {:dependencies [[ring-mock "0.1.5"]
                              [io.aviso/pretty "0.1.12"]
                              [clj-http "0.9.1" :exclusions [cheshire]]
                              [speclj "3.1.0"]
                              [log4j "1.2.17"]]}}
  ;; List "resolved" dependencies first, which occur when there are conflicts.
  ;; We pin down the version we want, then exclude anyone who disagrees.
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.3.1"]
                 [potemkin "0.3.8"]
                 [org.clojure/tools.reader "0.8.5"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.0"]
                 [io.aviso/tracker "0.1.1"]
                 [ring "1.3.1" :exclusions [org.clojure/tools.reader]]
                 [medley "0.5.0" :exclusions [com.keminglabs/cljx org.clojure/clojure]]
                 [ring-middleware-format "0.4.0" :excludes [cheshire]]
                 [prismatic/schema "0.2.6" :exclusions [potemkin]]
                 [metosin/ring-swagger "0.11.0"]
                 [metosin/ring-swagger-ui "2.0.17"]]
  :plugins [[speclj "3.1.0"]]
  :test-paths ["spec"]
  :codox {:src-dir-uri               "https://github.com/AvisoNovate/rook/blob/master/"
          :src-linenum-anchor-prefix "L"
          :defaults                  {:doc/format :markdown}})
