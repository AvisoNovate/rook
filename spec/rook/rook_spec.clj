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

          :get "/?offset-100" 'rook-test2 :function #'rook-test2/index))
    )

  (describe "argment resolution"
    (it "should use :arg-resolvers to calculate argument values"
        (let [test-mw (-> (namespace-middleware default-rook-pipeline 'rook-test)
                          param-handling)]

          (do-template [method path expected-result]
            (should= expected-result
                     (-> (mock/request method path) test-mw))

            :get "/?limit=100" {:status 200 :body "limit=100"}

            :get "/123" {:status 200 :body "id=123"}

            :post "/123/activate?test1=1test" "test1=1test,id=123,test2=,test3=,test4=,request=13,meth="

            :get "/123/activate" nil

            :put "/" nil

            :put "/123" nil)))

    (it "should operate with all types of arg-resolvers"
        (let [test-mw (-> (namespace-middleware default-rook-pipeline 'rook-test)
                          (arg-resolver-middleware
                            (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
                            (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request))))
                            #'request-arg-resolver)
                          param-handling)]
          (should= "test1=TEST!,id=123,test2=TEST@,test3=TEST#,test4=test$/123/activate,request=13,meth=:1234"
                   (->
                     (mock/request :post "/123/activate?test1=1test")
                     test-mw)))

        (let [map-resolver (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
              fn-resolver (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request))))
              arg-resolvers1 [map-resolver fn-resolver #'request-arg-resolver]
              arg-resolvers2 [#'request-arg-resolver map-resolver fn-resolver]
              handler (-> identity param-handling)
              test-request (->
                             (mock/request :post "/123/activate?test_value=TT!")
                             handler)]

          (do-template [arg-symbol resolvers expected]
            (should= expected
                     (extract-argument-value arg-symbol test-request resolvers))

            'test1 [map-resolver] "TEST!"
            'test2 [map-resolver] "TEST@"
            'test3 [map-resolver] "TEST#"

            'test1 arg-resolvers1 "TEST!"
            'test2 arg-resolvers1 "TEST@"
            'test3 arg-resolvers1 "TEST#"

            'test-value arg-resolvers1 "TT!"
            'test_value arg-resolvers1 "TT!"

            'test1 [fn-resolver] nil
            'test2 [fn-resolver] nil
            'test3 [fn-resolver] nil

            'request-method [map-resolver] :1234
            'request-method arg-resolvers1 :1234
            'request-method arg-resolvers2 :post

            'test4 arg-resolvers2 "test$/123/activate"

            ))))

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
        (should= "/:user-name/:password" uri)))))

(run-specs :color true)
