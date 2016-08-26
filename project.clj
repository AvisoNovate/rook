(defproject io.aviso/rook "0.2.0-SNAPSHOT"
  :description "Smart namespace-driven routing for Pedestal"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Sofware License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev {:dependencies [[speclj "3.3.2"]
                                  [io.aviso/logging "0.1.0"]
                                  [io.pedestal/pedestal.jetty "0.5.0"]
                                  [clj-http "3.1.0"]]}}
  ;; 1.8 or above is important; the way 1.7 does namespace metadata is a problem.
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.pedestal/pedestal.service "0.5.0"]
                 [clj-http "2.0.0"]]
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
