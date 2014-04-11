(ns rook.client-spec
  (:use
    [clojure.core.async :only [chan >!! <!!]]
    speclj.core)
  (:require
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

(describe "io.aviso.rook.client"


  (it "validates that request method and URI must be specified before send"

      (should-throw
        (-> (c/new-request :placeholder)
            c/send)))

  (it "validates that the request method is a known value."
      (should-throw (-> (c/new-request :placeholder)
                        (c/to :unknown "foo"))))

  (it "seperates path elements with a slash"
      (should= "/foo/23/skidoo"
               (-> (c/new-request :placeholder)
                   (c/to :get "foo" 23 :skidoo)
                   :ring-request
                   :uri)))

  (it "allows path to be omitted entirely"

      (should= "/"
               (-> (c/new-request :placeholder)
                   (c/to :get)
                   :ring-request
                   :uri)))

  (with response {:status  401
                  :headers {"content-length" 100 "content-type" "application/edn"}
                  })
  (with handler (constantly (respond @response)))

  (it "handles :failure by returning the full response (by default)"

      ;; Also shows that content-type and content-length headers are stripped.
      (should= {:status 401 :headers {}}
               (-> (c/new-request @handler)
                   (c/to :get)
                   c/send
                   <!!
                   first)))

  (it "can pass query parameters in the request"
      (let [params {:foo "foo" :bar ["biff baz"]}]
        (should= params
                 (-> (c/new-request :placeholder)
                     (c/with-query-params params)
                     :ring-request
                     :query-params))))

  (it "can pass body parameters in the request"
      (let [params {:foo 1 :bar 2}]
        (should= params
                 (-> (c/new-request :placeholder)
                     (c/with-body-params params)
                     :ring-request
                     :body-params))))

  (it "passes a Ring request to the handler"
      (should= {:request-method :put
                :uri            "/target"
                :headers        {"auth" "implied"}
                :query-params   {:page 1}
                :body-params           {:content :magic}
                :params         {:page 1 :content :magic}}
               (-> (c/new-request #(respond (utils/response 200 %)))
                   (c/to :put :target)
                   (c/with-headers {"auth" "implied"})
                   (c/with-query-params {:page 1})
                   (c/with-body-params {:content :magic})
                   c/send
                   <!!
                   second
                   ;; Filter out some extra information
                   (select-keys [:request-method :uri :headers :query-params :body-params :params]))))

  (it "overrides query parameters with body-parameters in the Ring request"
      (should= {:query-only :query-params
                :body-only  :body-params
                :both       :body-params}
               (-> (c/new-request #(respond (utils/response 200 %)))
                   (c/to :put :target)
                   (c/with-query-params {:query-only :query-params :both :query-params})
                   (c/with-body-params {:body-only :body-params :both :body-params})
                   c/send
                   <!!
                   second
                   :params)))

  (it "by default passes any success code through the success callback"
      ;; We could pass 200 thru 299 to prove a point ...
      (should= :response-body
               (-> (c/new-request (constantly (respond (utils/response 299 :response-body))))
                   (c/to :get)
                   c/send
                   <!!
                   second)))

  (it "converts an exception inside a try-go block into a 500"
      (should= 500
               (-> (c/new-request #(async/safe-go % (throw (IllegalArgumentException.))))
                   (c/to :get)
                   c/send
                   <!!
                   first
                   :status))))

(run-specs)
