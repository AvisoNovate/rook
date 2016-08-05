(defproject io.aviso/rook "0.2.0-SNAPSHOT"
  :description "Sane, smart, fast, Clojure web services"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev {:dependencies [[speclj "3.3.2"]
                             [io.aviso/logging "0.1.0"]
                             [clj-http "3.1.0"]]}}
  ;; List "resolved" dependencies first, which occur when there are conflicts.
  ;; We pin down the version we want, then exclude anyone who disagrees.
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.pedestal/pedestal.service "0.5.0"]]
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
