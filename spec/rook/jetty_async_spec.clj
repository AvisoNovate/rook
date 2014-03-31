(ns rook.jetty-async-spec
  (:import (javax.servlet.http HttpServletResponse))
  (:use speclj.core)
  (:require
    [clj-http.client :as client]
    [io.aviso.rook
     [async :as async]
     [jetty-async-adapter :as jetty]]))

(describe "io.aviso.rook.jetty-async-adapter"

  (with-all server
            (->
              (async/routes
                (async/namespace-handler "/fred" 'fred)
                (async/namespace-handler "/barney" 'barney))
              async/wrap-with-loopback
              async/wrap-with-standard-middleware
              (jetty/run-async-jetty {:port 9988 :join? false})))

  (it "did initialize the server successfully"
      (should-not-be-nil @server))

  (it "can process requests and return responses"

      (let [response (client/get "http://localhost:9988/fred" {:accept :json})]
        (should= HttpServletResponse/SC_OK (:status response))
        (should= "application/json; charset=utf-8" (-> response :headers (get "Content-Type")))
        (should= "{\"message\":\":barney says `ribs!'\"}" (:body response))))

  (it "can respond with a failure if the content is not valid"
      (let [response (client/post "http://localhost:9988/fred"
                                  {:accept           :edn
                                   :content-type     :edn
                                   :body             "{not valid EDN"
                                   :as               :clojure
                                   :throw-exceptions false})]
        #_ (clojure.pprint/pprint response)
        (should= 500 (:status response))
        (should= {:exception "EOF while reading"} (:body response))))

  (after-all
    (.stop @server)))

(run-specs :color true :reporters ["documentation"])