(ns rook.response-validation-spec
  (:import (javax.servlet.http HttpServletResponse))
  (:use
    speclj.core
    clojure.pprint
    ring.mock.request
    io.aviso.rook
    io.aviso.rook.response-validation)
  (:require [schema.core :as s]
            [clojure.core.async :refer [thread <!!]]
            [io.aviso.rook.async :as async]))

(describe "io.aviso.rook.response-validation"

  (context "synchronous"

    (with-all responses {200 {:player (s/enum :white :black)}
                         201 nil})

    (context "ensure-matching-response"

      (it "returns the response unchanged if valid"

          (let [response {:status 200 :body {:player :white}}]

            (should-be-same response
                            (ensure-matching-response response nil @responses))))

      (it "returns a 500 response if not valid"
          (let [actual-response (ensure-matching-response {:status 200 :body {:player :green}} "xyz/pdq" @responses)]
            (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR (:status actual-response))
            (should= "text/plain" (get-in actual-response [:headers "Content-Type"]))
            (->> actual-response
                 :body
                 (should-contain "Response validation failiure for xyz/pdq"))))

      (it "returns a 500 response if the status code isn't matched"
          (let [actual-response (ensure-matching-response {:status 400} "xyz/pdq" @responses)]
            (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR (:status actual-response))
            (should= "text/plain" (get-in actual-response [:headers "Content-Type"]))
            (->> actual-response
                 :body
                 (should= "Response from xyz/pdq was unexpected status code 400."))))

      (it "does not validate when the response body for the status is nil"
          (let [response {:status 201 :body {:player :white}}]

            (should-be-same response
                            (ensure-matching-response response nil @responses))))

      (it "passes 5xx responses through unchanged"
          (let [response {:status 504 :body {:other-system "foo"}}]
            (should-be-same response
                            (ensure-matching-response response nil @responses)))))

    (context "wrap-with-response-validation"

      (it "returns nil when not enabled"

          (should-be-nil
            (wrap-with-response-validation :handler nil false)))

      (it "returns nil when :responses not present"
          (should-be-nil (wrap-with-response-validation :handler nil)))

      (it "returns a validating handler when :responses present"
          (let [handler (constantly {:status 200 :body {:player :purple}})
                wrapped (wrap-with-response-validation handler {:function  "inline/handler"
                                                                :responses @responses})
                actual-response (wrapped nil)]
            (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                     (actual-response :status)))))

    (context "async/wrap-with-response-validation"

      (it "returns nil when not enabled"

          (should-be-nil
            (async/wrap-with-response-validation :handler nil false)))

      (it "returns nil when :responses not present"
          (should-be-nil (async/wrap-with-response-validation :handler nil)))

      (it "returns a validating handler when :responses present"
          (let [handler (fn [request] (thread {:status 200 :body {:player :purple}}))
                wrapped (async/wrap-with-response-validation handler {:function  "inline/handler"
                                                                      :responses @responses})
                actual-response (<!! (wrapped nil))]
            (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                     (actual-response :status)))))))

(run-specs)