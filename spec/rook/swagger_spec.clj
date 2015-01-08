(ns rook.swagger-spec
  (:import [java.util UUID Date]
           [javax.servlet.http HttpServletResponse])
  (:use io.aviso.rook
        speclj.core
        clojure.template
        clojure.pprint)
  (:require [io.aviso.rook.dispatcher :as d]
            [io.aviso.rook.swagger :as rs]
            [io.aviso.rook :as rook]
            [clj-http.client :as client]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [qbits.jet.server :as jet]
            [clojure.tools.logging :as l]))

(defn- swagger-data [options & ns-specs]
  (let [[_ routing-table] (d/construct-namespace-handler options ns-specs)]
    (rs/construct-swagger routing-table)))

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
  {"/hotels"
   {:description "The Hotels Resource."
    :routes      [{:method :get
                   :uri    "/hotels"
                   :metadata
                           {:summary          "Returns a list of all hotels, with control over sort order."
                            :return           200
                            :responseMessages [{:code          200
                                                :message       ""
                                                :responseModel [{:id         UUID
                                                                 :created_at Date
                                                                 :updated_at Date
                                                                 :name       String}]}]
                            :parameters       []}}
                  {:method :get
                   :uri    "/hotels/{id}"
                   :metadata
                           {:summary          "Returns a single hotel, if found."
                            :return           200
                            :responseMessages [{:code          200
                                                :message       ""
                                                :responseModel {:id         UUID
                                                                :created_at Date
                                                                :updated_at Date
                                                                :name       String}}
                                               {:code          404
                                                :message       ""
                                                :responseModel nil}]
                            :parameters       [{:type  :path
                                                :model {:id String}}]}}]}
   "/hotels/{hotel-id}/rooms"
   {:description "A set of rooms within a specific hotel."
    :routes      [{:method :post
                   :uri    "/hotels/{hotel-id}/rooms"
                   :metadata
                           {:summary          "No endpoint description provided."
                            :return           nil
                            :responseMessages ()
                            :parameters
                                              [{:type  :path
                                                :model {:hotel-id String}}]}}]}})

(describe "io.aviso.rook.swagger"

  (it "can construct swagger data"
      (let [data (swagger-data nil
                               ["hotels" 'hotels
                                [[:hotel-id "rooms"] 'rooms]])]
        (should= expected-swagger-data data)))

  (context "end-to-end (synchronous)"

    (with-all handler (-> (rook/namespace-handler {:swagger true}
                                                  ["hotels" 'hotels
                                                   [[:hotel-id "rooms"] 'rooms]])
                          rook/wrap-with-standard-middleware))

    (with-all server (jet/run-jetty {:ring-handler @handler
                                     :join? false
                                     :port 8192}))

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

(run-specs)
