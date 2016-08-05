(defproject io.aviso/rook "0.2.0-SNAPSHOT"
  :description "Sane, smart, fast, Clojure web services"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles :dev
  {:dependencies [[ring-mock "0.1.5"]
                  [cc.qbits/jet "0.7.10"]
                  [speclj "3.3.2"]
                  [io.aviso/logging "0.1.0"]
                  [clj-http "3.1.0"]]}
  ;; List "resolved" dependencies first, which occur when there are conflicts.
  ;; We pin down the version we want, then exclude anyone who disagrees.
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [io.pedestal/pedestal.service "0.5.0"]
                 [io.aviso/pretty "0.1.29"]
                 [io.aviso/toolchest "0.1.4"]
                 [cheshire "5.6.3"]
                 [riddley "0.1.12"]
                 [potemkin "0.4.3" :_exclusions [[riddley]]]
                 ;; A  conflict between clj-http (dev) and ring-middleware-format
                 ;[org.clojure/tools.reader "0.9.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [io.aviso/tracker "0.1.7"]
                 [medley "0.8.2"]
                 [ring-middleware-format "0.7.0" :_exclusions [org.clojure/tools.reader ring]]
                 [ring/ring-core "1.5.0" :_exclusions [commons-fileupload]]
                 [prismatic/schema "1.1.3"]]
  :plugins [[speclj "3.3.2"]
            [walmartlabs/vizdeps "0.1.1"]
            [lein-codox "0.9.5"]]
  :jvm-opts ["-Xmx1g"]
  :aliases {"release" ["do"
                       "clean,"
                       "spec,",
                       "deploy" "clojars"]
            ;; Redirect lein ancient to use spec to check that dependencies upgrades are ok.
            "test" ["spec"]}
  :test-paths ["spec"]
  :codox {:source-uri "https://github.com/AvisoNovate/rook/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}})
