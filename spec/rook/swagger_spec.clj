(ns rook.swagger-spec
  (:use io.aviso.rook
        speclj.core
        clojure.repl
        clojure.template
        clojure.pprint)
  (:require [io.aviso.rook.dispatcher :as d]
            [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [io.aviso.rook.swagger :as rs]
            [io.aviso.rook :as rook]
            [clj-http.client :as client]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [qbits.jet.server :as jet]
            [clojure.tools.logging :as l]))

(defn- swagger-object
  [rook-options swagger-options & ns-specs]
  (let [[_ routing-table] (d/construct-namespace-handler rook-options ns-specs)]
    (rs/construct-swagger-object swagger-options routing-table)))

(defn get*
  [path]
  (client/get (str "http://localhost:8192" path)
              {:throw-exceptions false
               :content-type     :edn
               :accept           :edn
               :as               :clojure}))

(defmacro should-have-status
  [expected-status response]
  `(let [response# ~response
         expected-status# ~expected-status]
     (if (= expected-status# (:status response#))
       response#
       (-fail (format "Expected status %d in response:\n%s"
                      expected-status#
                      (pretty-print response#))))))

(defn tap
  [message data]
  (l/debugf "%s: %s" message (pretty-print data))
  data)

(def expected-swagger-data
  {})


(-> (swagger-object nil rs/default-swagger-options
                    ["hotels" 'hotels
                     [[:hotel-id "rooms"] 'rooms]])
    (json/generate-string {:pretty true})
    println)

#_
(describe "io.aviso.rook.swagger"

  (it "can construct swagger data"
      (let [data (swagger-object nil nil
                                 ["hotels" 'hotels
                                  [[:hotel-id "rooms"] 'rooms]])]
        #_ (should= expected-swagger-data data)))

  (context "end-to-end (synchronous)"

    (with-all handler (-> (rook/namespace-handler {:swagger true}
                                                  ["hotels" 'hotels
                                                   [[:hotel-id "rooms"] 'rooms]])
                          rook/wrap-with-standard-middleware))

    (with-all server (jet/run-jetty {:ring-handler @handler
                                     :join?        false
                                     :port         8192}))

    (it "can start the server"
        (should-not-be-nil @server))

    (after-all
      (.stop @server))

    (it "can expose the resource listing"

        (->> (get* "/swagger")
             (should-have-status HttpServletResponse/SC_OK)
             #_ (tap "response")
             :body
             :apis
             (map :path)
             sort
             ;; This is wrong and a fix is coming:
             (should= ["/hotels" "/hotels/{hotel-id}/rooms"])))))

#_ (run-specs)
