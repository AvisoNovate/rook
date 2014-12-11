(ns rook.jetty-async-spec
  (:import (javax.servlet.http HttpServletResponse))
  (:use
  speclj.core
  clojure.pprint)
  (:require
    [clojure.edn :as edn]
    [clojure.core.async :refer [thread]]
    [clj-http
     [cookies :as cookies]
     [client :as client]]
    [io.aviso.rook :as rook]
    [io.aviso.rook
     [async :as async]
     [utils :as utils]
     [clj-http :as ch]
     [jetty-async-adapter :as jetty]]))

(describe "io.aviso.rook.jetty-async-adapter"

  (with-all handler
            (ch/handler "http://localhost:9988/"))

  (with-all server
            (-> (rook/namespace-handler {:async?        true
                                         :arg-resolvers {'loopback-handler :injection}}
                                        ["fred" 'fred]
                                        ["barney" 'barney]
                                        ["slow" 'slow]
                                        ["sessions" 'sessions]
                                        ["creator" 'creator]
                                        ["creator-loopback" 'creator-loopback])
                async/wrap-session
                async/wrap-with-standard-middleware
                (rook/wrap-with-injection :loopback-handler @handler)
                (jetty/run-async-jetty {:port 9988 :join? false :async-timeout 100})))

  (it "did initialize the server successfully"
      (should-not-be-nil @server))

  (it "can process requests and return responses"

      (let [response (client/get "http://localhost:9988/fred" {:accept :json})]
        (should= HttpServletResponse/SC_OK (:status response))
        (should= "application/json; charset=utf-8" (-> response :headers (get "Content-Type")))
        (should= "{\"message\":\":barney says `ribs!'\"}" (:body response))))

  (it "will respond with a failure if the content is not valid"
      (let [response (client/post "http://localhost:9988/fred"
                                  {:accept           :edn
                                   :content-type     :edn
                                   :body             "{not valid EDN"
                                   :as               :clojure
                                   :throw-exceptions false})]
        (should= 500 (:status response))
        (should= {:exception "EOF while reading"} (:body response))))

  (it "can manage server-side session state"
      (let [key (utils/new-uuid)
            value (utils/new-uuid)
            url "http://localhost:9988/sessions/"
            store (cookies/cookie-store)
            response (client/post (str url key "/" value)
                                  {:accept       :edn
                                   :cookie-store store})
            _ (should= 200 (:status response))
            _ (should= (pr-str {:result :ok}) (:body response))
            response' (client/get (str url key)
                                  {:accept           :edn
                                   :cookie-store     store
                                   :throw-exceptions false})]
        (should= 200 (:status response'))
        (should= value
                 (-> response' :body edn/read-string :result))))

  (it "handles a slow handler timeout"
      (let [response (client/get "http://localhost:9988/slow"
                                 {:accept           :json
                                  :throw-exceptions false})]
        (should= HttpServletResponse/SC_GATEWAY_TIMEOUT (:status response))))

  (it "responds with 404 if no handler can be found"
      (let [response (client/get "http://localhost:9988/wilma"
                                 {:throw-exceptions false})]
        (should= HttpServletResponse/SC_NOT_FOUND (:status response))))

  (it "can still calculate :resource-uri even after a loopback"
      (let [response (client/post "http://localhost:9988/creator-loopback"
                                  {:throw-exceptions false})]
        (should= "http://localhost:9988/creator/<ID>"
                 (get-in response [:headers "Location"]))))

  (after-all
    (.stop @server)))

(run-specs)
