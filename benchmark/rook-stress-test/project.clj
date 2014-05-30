(defproject rook-stress-test "0.1.0-SNAPSHOT"
  :description "Utility for benchmarking Rook dispatch"
  :url "https://github.com/AvisoNovate/rook"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [io.aviso/rook "0.1.10-SNAPSHOT"]
                 [ring "1.2.2"]]
  :jvm-opts ^:replace [])
