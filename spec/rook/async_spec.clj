(ns rook.async-spec
  (:import (javax.servlet.http HttpServletResponse))
  (:use
    [clojure.core.async :only [go chan >!! <! <!! thread]]
    speclj.core)
  (:require
    [ring.mock.request :as mock]
    [io.aviso.rook :as rook]
    [io.aviso.rook
     [async :as async]
     [client :as c]
     [utils :as utils]]))

(defn- invoke [handler] (handler {}))

(describe "io.aviso.rook.async"

  (describe "result->channel"

    (it "returns most values normally"
        (should= :anything
                 (-> :anything
                     async/result->channel
                     <!!))))

  (describe "ring-handler->async-handler"

    (it "executes on a different thread"

        (should-not-be-same (Thread/currentThread)
                            (->
                              (fn [_] (Thread/currentThread))
                              async/ring-handler->async-handler
                              invoke
                              <!!))))


  (describe "end-to-end test"

    (it "properly sends schema validation failures"
        (let [handler (->
                        (rook/namespace-handler
                          {:async? true}
                          ["validating" 'validating async/wrap-with-schema-validation])
                        async/async-handler->ring-handler)
              response (-> (mock/request :post "/validating")
                           handler)]
          (should= HttpServletResponse/SC_BAD_REQUEST
                   (:status response))
          (should= "validation-error" (-> response :body :error))
          ;; TODO: Not sure that's the exact format I want sent back to the client!
          (should= "{:name missing-required-key}" (-> response :body :failures))))


    (it "returns a 500 response if a sync handler throws an error"
        (let [handler (->
                        (rook/namespace-handler
                          {:async? true}
                          ["fail" 'failing])
                        async/async-handler->ring-handler)]
          (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                   (-> (mock/request :get "/fail")
                       handler
                       :status))))))

(run-specs)
