(defproject io.aviso/rook "0.1.26-SNAPSHOT"
            :description "Sane, smart, fast, Clojure web services"
            :url "http://howardlewisship.com/io.aviso/documentation/rook"
            :license {:name "Apache Sofware License 2.0"
                      :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
            :profiles {:dev
                        {:dependencies [[ring-mock "0.1.5"]
                                        [cc.qbits/jet "0.5.4"]
                                        [speclj "3.1.0"]
                                        [log4j "1.2.17"]]}}
            ;; List "resolved" dependencies first, which occur when there are conflicts.
            ;; We pin down the version we want, then exclude anyone who disagrees.
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [io.aviso/pretty "0.1.15"]
                           [io.aviso/toolchest "0.1.1"]
                           [cheshire "5.4.0"]
                           [potemkin "0.3.11"]
                           ;; This overrides the version from ring/ring-core with the version from metosin/ring-swagger
                           ;; Can't simply exclude it from ring-core, because ring-swagger is an optional dependency.
                           [clj-time "0.9.0"]
                           ;; Likewise, the conflict between clj-http (optional) and ring-middleware-format over
                           [com.cognitect/transit-clj "0.8.259"]
                           [org.clojure/tools.reader "0.8.13"]
                           [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                           [org.clojure/tools.logging "0.3.1"]
                           [io.aviso/tracker "0.1.4"]
                           [ring/ring-core "1.3.2" :exclusions [org.clojure/tools.reader]]
                           [medley "0.5.5" :exclusions [com.keminglabs/cljx org.clojure/clojure]]
                           [ring-middleware-format "0.4.0" :exclusions [cheshire ring/ring-devel ring/ring-jetty-adapter]]
                           [prismatic/schema "0.3.7" :exclusions [potemkin]]
                           ;; ring-core and ring-swagger have a conflict w.r.t clj-time "0.6.0" vs. "0.9.0".
                           [metosin/ring-swagger "0.15.0" :optional true :exclusions [org.clojure/clojure]]
                           [metosin/ring-swagger-ui "2.0.17" :optional true]
                           [clj-http "1.0.1" :optional true]]
            :plugins [[speclj "3.1.0"]
                      [lein-shell "0.4.0"]]
            :jvm-opts ["-Xmx1g"]
            :shell {:commands {"scp" {:dir "doc"}}}
            :aliases {"deploy-doc" ["shell"
                                    "scp" "-r" "." "hlship_howardlewisship@ssh.phx.nearlyfreespeech.net:io.aviso/rook"]
                      "release"    ["do"
                                    "clean,"
                                    "spec,",
                                    "doc,"
                                    "deploy-doc,"
                                    "deploy" "clojars"]}
            :test-paths ["spec"]
            :codox {:src-dir-uri               "https://github.com/AvisoNovate/rook/blob/master/"
                    :src-linenum-anchor-prefix "L"
                    :defaults                  {:doc/format :markdown}})
