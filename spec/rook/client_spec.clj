(ns rook.client-spec
  (:import (javax.servlet.http HttpServletResponse))
  (:use
  [clojure.core.async :only [chan >!! <!! <! go]]
  speclj.core)
  (:require
    [ring.util.response :as r]
    [io.aviso.rook
     [async :as async]
     [client :as c]
     [utils :as utils]]))

(defn- respond
  "Async respons with value, as if computed by a go or thread block."
  [value]
  (let [c (chan 1)]
    (>!! c value)
    c))

(defn responder
  [value]
  (fn [request] (respond value)))

(describe "io.aviso.rook.client"

  (it "validates that request method and URI must be specified before send"

      (should-throw
        (-> (c/new-request :placeholder)
            c/send)))

  (it "validates that the request method is a known value."
      (should-throw (-> (c/new-request :placeholder)
                        (c/to :unknown "foo"))))

  (it "seperates path elements with a slash"
      (should= "foo/23/skidoo"
               (-> (c/new-request :placeholder)
                   (c/to :get "foo" 23 :skidoo)
                   :uri)))

  (it "allows path to be omitted entirely"

      (should= ""
               (-> (c/new-request :placeholder)
                   (c/to :get)
                   :uri)))

  (with response {:status  401
                  :headers {"content-length" 100 "content-type" "application/edn"}})

  (with handler (constantly (respond @response)))

  (it "handles :failure by returning the full response (by default)"

      (should= @response
               (-> (c/new-request @handler)
                   (c/to :get)
                   c/send
                   <!!)))

  (it "can pass query parameters in the request"
      (let [params {:foo "foo" :bar ["biff baz"]}]
        (should= params
                 (-> (c/new-request :placeholder)
                     (c/with-query-params params)
                                          :query-params))))

  (it "can pass body parameters in the request"
      (let [params {:foo 1 :bar 2}]
        (should= params
                 (-> (c/new-request :placeholder)
                     (c/with-body-params params)
                                          :body-params))))

  (it "passes a Ring request to the handler"
      (should= {:request-method :put
                :uri            "target"
                :headers        {"auth" "implied"}
                :query-params   {:page 1}
                :body-params    {:content :magic}}
               (-> (c/new-request #(respond (utils/response 200 %)))
                   (c/to :put :target)
                   (c/with-headers {"auth" "implied"})
                   (c/with-query-params {:page 1})
                   (c/with-body-params {:content :magic})
                   c/send
                   <!!
                   :body
                   ;; Filter out some extra information
                   (select-keys [:request-method :uri :headers :query-params :body-params]))))

  (it "converts an exception inside a try-go block into a 500"
      (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR
               (-> (c/new-request #(async/safe-go % (throw (IllegalArgumentException.))))
                   (c/to :get)
                   c/send
                   <!!
                   :status)))

  (context "then macro"

    (it "can match a specific status"
        (should= "12345"
                 (-> (c/new-request (responder (-> (utils/response HttpServletResponse/SC_CREATED)
                                                   (r/header "Inserted-Id" "12345"))))
                     (c/to :get)
                     c/send
                     (c/then HttpServletResponse/SC_CREATED ([response] (get-in response [:headers "Inserted-Id"])))
                     go
                     <!!)))

    (it "allows the matched clause to be a map destructuring"
        (should= HttpServletResponse/SC_CREATED
                 (-> (c/new-request (responder (-> (utils/response HttpServletResponse/SC_CREATED)
                                                   (r/header "Inserted-Id" "12345"))))
                     (c/to :get)
                     c/send
                     (c/then 201 ([{:keys [status]}] status))
                     go
                     <!!)))

    (it "can match on general :success"
        (should= HttpServletResponse/SC_NO_CONTENT
                 (-> (c/new-request (responder (utils/response HttpServletResponse/SC_NO_CONTENT)))
                     (c/to :get)
                     c/send
                     (c/then :failure ([response] :not-matched)
                             :success ([response] (:status response)))
                     go
                     <!!)))

    (it "can match on general :failure"
        (should= HttpServletResponse/SC_BAD_REQUEST
                 (-> (c/new-request (responder (utils/response HttpServletResponse/SC_BAD_REQUEST)))
                     (c/to :get)
                     c/send
                     (c/then :failure ([response] (:status response))
                             :success ([response] :not-matched))
                     go
                     <!!)))

    (it "will match anything using :else"
        (should= HttpServletResponse/SC_BAD_REQUEST
                 (-> (c/new-request (responder (utils/response HttpServletResponse/SC_BAD_REQUEST)))
                     (c/to :get)
                     c/send
                     (c/then HttpServletResponse/SC_CREATED ([response] :not-matched)
                             :else ([response] (:status response)))
                     go
                     <!!)))

    (it "can pass a success result through unchanged"
        (should= (utils/response HttpServletResponse/SC_OK)
                 (-> (c/new-request (responder (utils/response HttpServletResponse/SC_OK)))
                     (c/to :get)
                     c/send
                     (c/then :pass-success)
                     go
                     <!!)))

    (it "can pass a failure result through unchanged"
        (should= (utils/response HttpServletResponse/SC_CONFLICT)
                 (-> (c/new-request (responder (utils/response HttpServletResponse/SC_CONFLICT)))
                     (c/to :get)
                     c/send
                     (c/then :pass-failure)
                     go
                     <!!)))


    (it (str "recognizes :pass as an alternative to a vector ")
        (let [response {:status HttpServletResponse/SC_OK}]
          (should= response
                   (c/then* response
                            :success :pass))))

    (it "throws an exception if there is no match"
        (should= {:caught "Unmatched status code 400 processing response."}
                 (->
                   (go
                     (try (->
                            (c/new-request (responder (utils/response HttpServletResponse/SC_BAD_REQUEST)))
                            (c/to :get)
                            c/send
                            (c/then HttpServletResponse/SC_CREATED ([response] :not-matched)))
                          ;; The try must be inside the go for the exception to not just get sort of dropped.
                          ;; Rook includes safe-go to transmute exceptions into 500 responses.
                          ;; This proves the exception was triggered.
                          (catch Throwable t
                            {:caught (.getMessage t)})))
                   <!!)))))

(run-specs)
