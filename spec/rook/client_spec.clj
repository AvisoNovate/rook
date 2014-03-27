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
                     (c/with-parameters params)
                     :ring-request
                     :params))))

  (it "can pass a body in the request"
      (let [body {:foo 1 :bar 2}]
        (should= body
                 (-> (c/new-request :placeholder)
                     (c/with-body body)
                     :ring-request
                     :body))))

  (it "passes a Ring request to the handler"
      (should= {:request-method :put
                :uri            "/target"
                :headers        {"auth" "implied"}
                :params         {:page 1}
                :body           {:content :magic}}
               (-> (c/new-request #(respond (utils/response 401 %)))
                   (c/to :put :target)
                   (c/with-headers {"auth" "implied"})
                   (c/with-parameters {:page 1})
                   (c/with-body {:content :magic})
                   c/send
                   ;; 401 response is passed through as-is
                   <!!
                   first
                   ;; body of response is the incoming request.
                   :body
                   ;; Filter out some extra information
                   (select-keys [:request-method :uri :headers :params :body]))))

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
               (-> (c/new-request #(async/try-go % (throw (IllegalArgumentException.))))
                   (c/to :get)
                   c/send
                   <!!
                   first
                   :status))))

(run-specs :color true)