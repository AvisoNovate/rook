(defproject rook-example "0.0.1"
            :description "An example rook resource"
            :main org.example.server/main
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [io.aviso/rook "0.1.37"]
                           [ring "1.3.2"]]
            :plugins [[speclj "3.2.0"]]
            :test-paths ["spec"]
            :profiles {:dev {:dependencies [[speclj "3.2.0"]
                                            [clj-http "1.0.1"]
                                            [log4j "1.2.17"]]}})