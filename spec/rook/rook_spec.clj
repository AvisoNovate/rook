(ns rook.rook-spec
  (:use
    io.aviso.rook
    io.aviso.rook.internals
    speclj.core
    clojure.template)
  (:require
    rook-test
    rook-test2
    rook-test3
    rook-test4
    rook-test5
    rook-test6
    [ring.mock.request :as mock]
    ring.middleware.params
    ring.middleware.keyword-params
    [compojure.core :as compojure]))


(defn param-handling [handler]
  (-> handler
      wrap-with-default-arg-resolvers
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(defn mkrequest [method path namespace]
  (let [handler (-> (namespace-middleware (-> identity wrap-with-function-arg-resolvers) namespace)
                    param-handling)]
    (handler (mock/request method path))))

(describe "io.aviso.rook"
  (describe "namespace-middleware"

    (it "should match incoming URIs and methods to functions"
        (do-template [method path namespace-name rook-key expected-value]
          (should= expected-value
                   (-> (mkrequest method path namespace-name)
                       (get-in [:rook rook-key])))

          :get "/?limit=100" 'rook-test :function #'rook-test/index

          :get "/" 'rook-test :namespace 'rook-test

          :get "/123" 'rook-test :function #'rook-test/show

          :post "/123/activate" 'rook-test :function #'rook-test/activate

          :get "/123/activate" 'rook-test :function nil

          :put "/" 'rook-test :function nil

          :put "/123" 'rook-test :function nil

          :get "/?offset-100" 'rook-test2 :function #'rook-test2/index)))

  (describe "argment resolution"
    (it "should use :arg-resolvers to calculate argument values"
        (let [test-mw (-> (namespace-middleware default-rook-pipeline 'rook-test)
                          param-handling)]

          (do-template [method path headers expected-result]
            (should= expected-result
                     (-> (mock/request method path)
                         (assoc :headers headers)
                         test-mw))

            :get "/?limit=100"  nil {:status 200 :body "limit=100"}

            :get "/123" nil {:status 200 :body "id=123"}

            :post "/123/activate?test1=1test" nil "test1=1test,id=123,test2=,test3=,test4=,request=13,meth="

            :get "/123/activate" nil nil

            :post "/456/if-modified-since" {"if-modified-since" "time-instant"} {:id "456" :if-modified-since "time-instant"}

            :put "/" nil nil

            :put "/123" nil nil)))

    (it "should expose the request's :params key as an argument"
        (let [handler (->
                        (namespace-handler 'echo-params)
                        wrap-with-default-arg-resolvers)
              params {:foo :bar}]
          (should-be-same params
            (->
              (mock/request :get "/")
              (assoc :params params)
              handler
              :body
              :params-arg))))

    (it "should operate with all types of arg-resolvers"
        (let [test-mw (-> (namespace-middleware default-rook-pipeline 'rook-test)
                          (arg-resolver-middleware
                            (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
                            (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request)))))
                          param-handling)]
          (should= "test1=TEST!,id=123,test2=TEST@,test3=TEST#,test4=test$/123/activate,request=13,meth=:1234"
                   (->
                     ;; So we've set up a specific conflict for test1 ... is it the query parameter value "1test"
                     ;; or is it the arg-resolver value "TEST!". The correct answer is "TEST!" because
                     ;; that arg-resolver is added later in processing, and is therefore deemed to be more
                     ;; specific.
                     (mock/request :post "/123/activate?test1=1test")
                     test-mw)))))

  (describe "nested contexts"

    (it "should match URIs and methods to specific functions"
        (let [test-mw (-> (compojure/context "/merchant" []
                                             (namespace-middleware
                                               (compojure/routes
                                                 (compojure/context "/:id/activate" []
                                                                    (namespace-middleware
                                                                      (compojure/routes
                                                                        (compojure/context "/:key" []
                                                                                           (namespace-middleware
                                                                                             default-rook-pipeline 'rook-test3))
                                                                        default-rook-pipeline)
                                                                      'rook-test2))
                                                 default-rook-pipeline)
                                               'rook-test))
                          param-handling)
              test-mw2 (-> (namespace-handler "/merchant" 'rook-test
                                              (compojure/routes
                                                (compojure/GET "/test" [] {:body "test!"})
                                                default-rook-pipeline (namespace-handler "/:id/activate" 'rook-test2
                                                                                         (compojure/routes
                                                                                           default-rook-pipeline
                                                                                           (namespace-handler "/:key" 'rook-test3)))))
                           param-handling)
              test-mw4 (-> (namespace-handler "/test4" 'rook-test4)
                           (arg-resolver-middleware request-arg-resolver))]
          (do-template [handler method path expected-result]
            (should= expected-result
                     (handler (mock/request method path)))

            test-mw :post "/456/activate" nil

            test-mw :get "/merchant/" {:status 200 :body "limit="}

            test-mw :get "/merchant/6789" {:status 200 :body "id=6789"}

            test-mw :get "/merchant/4567/activate?offset=1234" {:body "id=4567&offset=1234"}

            test-mw :get "/merchant/4567/activate/test_key" {:body "test3,id=4567,key=test_key"}

            test-mw2 :post "/456/activate" nil

            test-mw2 :get "/merchant/" {:status 200 :body "limit="}

            test-mw2 :get "/merchant/6789" {:status 200 :body "id=6789"}

            test-mw2 :get "/merchant/4567/activate?offset=1234" {:body "id=4567&offset=1234"}

            test-mw2 :get "/merchant/4567/activate/test_key" {:body "test3,id=4567,key=test_key"}

            test-mw2 :get "/merchant/test" {:status 200 :headers {} :body "test!"}

            test-mw4 :get "/test4/proxy" "method=GET"

            test-mw4 :put "/test4/proxy" "method=PUT"))))

  (describe "function ordering within namespace"
    (it "should order functions by line number"

        (let [paths (get-available-paths 'rook-test5)
              funcs (map #(nth % 2) paths)]

          (should= [#'rook-test5/show-default #'rook-test5/show] funcs))))

  (describe "meta data support"

    (it "should merge meta-data from the namespace into :metadata"

        (let [show-meta (->> 'rook-test5
                             get-available-paths
                             (filter (fn [[_ _ _ meta]] (= 'show (:name meta))))
                             first
                             last)]
          (do-template [key expected]
            (should= expected (get show-meta key))
            :inherited :namespace
            :overridden :function)))

    (it "should not conflict when a function with a convention name has overriding :path-spec meta-data"
        (let [paths (get-available-paths 'rook-test6)
              _ (should= 1 (count paths))
              [method uri] (first paths)]
          (should= :post method)
          (should= "/:user-name/:password" uri))))

  (describe ":resource-uri argument resolver"

    (with handler (-> (namespace-handler nil 'creator
                                         (compojure/routes
                                           (namespace-handler "/nested" 'creator)
                                           default-rook-pipeline))
                      wrap-with-standard-middleware))
    (with request {:scheme         :http
                   :server-name    "rook.aviso.io"
                   :server-port    80
                   :request-method :post})

    (it "resolves the correct value for a top-level resource"
        (let [h @handler]
          (should= "http://rook.aviso.io/<ID>"
                   (-> @request
                       (assoc :uri "/")
                       h
                       (get-in [:headers "Location"])))))

    (it "resolves the correct value for a nested resource"
        (let [h @handler]
          (should= "http://rook.aviso.io/nested/<ID>"
                   (-> @request
                       (assoc :uri "/nested")
                       h
                       (get-in [:headers "Location"])))))

    (it "will use the :server-uri key if present"
        (should= "http://overrride.com/api/"
                 (resource-uri-arg-resolver {:server-uri "http://overrride.com"
                                             :context    "/api"})))

    (it "will include the port number if not matching the scheme default"
        (should= "http://server.com:81/"
                 (resource-uri-arg-resolver {:scheme      :http
                                             :server-port 81
                                             :server-name "server.com"}))

        (should= "https://server.com:232/"
                 (resource-uri-arg-resolver {:scheme      :https
                                             :server-port 232
                                             :server-name "server.com"})))))


(run-specs)
