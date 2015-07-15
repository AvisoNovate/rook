(defproject io.aviso/rook "0.1.34"
            :description "Sane, smart, fast, Clojure web services"
            :url "http://howardlewisship.com/io.aviso/documentation/rook"
            :license {:name "Apache Sofware License 2.0"
                      :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
            :profiles {:dev
                       {:dependencies [[ring-mock "0.1.5"]
                                       [cc.qbits/jet "0.6.6" :exclusions [org.clojure/clojure]]
                                       [speclj "3.3.1" :exclusions [org.clojure/clojure]]
                                       [ch.qos.logback/logback-classic "1.1.3"]
                                       [clj-http "1.1.2"]]}}
            ;; List "resolved" dependencies first, which occur when there are conflicts.
            ;; We pin down the version we want, then exclude anyone who disagrees.
            :dependencies [[org.clojure/clojure "1.7.0"]
                           [io.aviso/pretty "0.1.18"]
                           [io.aviso/toolchest "0.1.2"]
                           [cheshire "5.5.0"]
                           [potemkin "0.3.13"]
                           ;; A  conflict between clj-http (dev) and ring-middleware-format
                           [org.clojure/tools.reader "0.9.2"]
                           [org.clojure/tools.logging "0.3.1"]
                           [io.aviso/tracker "0.1.6"]
                           [medley "0.7.0" :exclusions [com.keminglabs/cljx org.clojure/clojure]]
                           [ring-middleware-format "0.5.0" :exclusions [ring/ring-devel org.clojure/tools.reader ring
                                                                        com.cognitect/transit-clj
                                                                        com.cognitect/transit-java]]
                           [ring/ring-core "1.4.0" :exclusions [commons-fileupload]]
                           [prismatic/schema "0.4.3"]]
            :plugins [[speclj "3.3.1" :exclusions [org.clojure/clojure]]
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
                                    "deploy" "clojars"]
                      ;; Redirect lein ancient to use spec to check that dependencies upgrades are ok.
                      "test"       ["spec"]}
            :test-paths ["spec"]
            :codox {:src-dir-uri               "https://github.com/AvisoNovate/rook/blob/master/"
                    :src-linenum-anchor-prefix "L"
                    :defaults                  {:doc/format :markdown}})
