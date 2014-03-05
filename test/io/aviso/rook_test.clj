(ns io.aviso.rook-test
  (:use
    io.aviso.rook
    clojure.test)
  (:require
    [io.aviso.rook-test2]
    [io.aviso.rook-test3]
    [ring.mock.request :as mock]
    [ring.middleware.params]
    [ring.middleware.keyword-params]
    [compojure.core :as compojure]))

(defn index [limit]
  {:status 200
   :body   (str "limit=" limit)})

(defn show [id]
  {:status 200
   :body   (str "id=" id)})

(defn
  activate
  {:path-spec [:post "/:id/activate"]}
  [test1 id request test2 test3 test4 request-method]
  (str "test1=" test1
       ",id=" id
       ",test2=" test2,
       ",test3=" test3,
       ",test4=" test4,
       ",request=" (count request)
       ",meth=" request-method))


(defn param-handling [handler]
  (-> handler
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(defn mkrequest [method path namespace]
  (let [handler (-> (namespace-middleware identity namespace)
                    param-handling)]
    (handler (mock/request method path))))

(defn u-show [id]
  (str "!" id "!"))

(deftest namespace-middleware-test

  (are [method path namespace expected-function]
    (= expected-function
       (-> (mkrequest method path namespace)
           (get-in [:rook :function])))

    :get "/?limit=100" 'io.aviso.rook-test #'index

    :get "/123" 'io.aviso.rook-test #'show

    :post "/123/activate" 'io.aviso.rook-test #'activate

    :get "/123/activate" 'io.aviso.rook-test nil

    :put "/" 'io.aviso.rook-test nil

    :put "/123" 'io.aviso.rook-test nil

    :get "/?offset-100" 'io.aviso.rook-test2 #'io.aviso.rook-test2/index))

(deftest namespace-handler-test

  (let [test-mw (-> (namespace-middleware rook-handler 'io.aviso.rook-test)
                    param-handling)]

    (are [method path expected-result]
      (= expected-result
         (-> (mock/request method path) test-mw))

      :get "/?limit=100" {:status 200 :body "limit=100"}

      :get "/123" {:status 200 :body "id=123"}

      :post "/123/activate?test1=1test" "test1=1test,id=123,test2=,test3=,test4=,request=13,meth="

      :get "/123/activate" nil

      :put "/" nil

      :put "/123" nil)))


(deftest complete-handler-test
  (let [test-mw (-> (namespace-middleware rook-handler 'io.aviso.rook-test)
                    (arg-resolver-middleware
                      (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
                      (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request))))
                      #'request-arg-resolver)
                    param-handling)]
    (is (= "test1=TEST!,id=123,test2=TEST@,test3=TEST#,test4=test$/123/activate,request=13,meth=:1234"
           (->
             (mock/request :post "/123/activate?test1=1test")
             test-mw)))))

(deftest arg-resolver-test
  (let [map-resolver (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
        fn-resolver (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request))))
        arg-resolvers1 [map-resolver fn-resolver #'request-arg-resolver]
        arg-resolvers2 [#'request-arg-resolver map-resolver fn-resolver]
        handler (-> identity param-handling)
        test-request (->
                       (mock/request :post "/123/activate?test_value=TT!")
                       handler)]
    (is (= "TEST!" (extract-argument-value 'test1 test-request [map-resolver])))
    (is (= "TEST@" (extract-argument-value 'test2 test-request [map-resolver])))
    (is (= "TEST#" (extract-argument-value 'test3 test-request [map-resolver])))
    (is (= "TEST!" (extract-argument-value 'test1 test-request arg-resolvers1)))
    (is (= "TEST@" (extract-argument-value 'test2 test-request arg-resolvers1)))
    (is (= "TEST#" (extract-argument-value 'test3 test-request arg-resolvers1)))
    (is (= "TT!" (extract-argument-value 'test-value test-request arg-resolvers1)))
    (is (= "TT!" (extract-argument-value 'test_value test-request arg-resolvers1)))
    (is (nil? (extract-argument-value 'test1 test-request [fn-resolver])))
    (is (nil? (extract-argument-value 'test2 test-request [fn-resolver])))
    (is (nil? (extract-argument-value 'test3 test-request [fn-resolver])))
    (is (= :1234 (extract-argument-value 'request-method test-request [map-resolver])))
    (is (= :1234 (extract-argument-value 'request-method test-request arg-resolvers1)))
    (is (= :post (extract-argument-value 'request-method test-request arg-resolvers2)))
    (is (= "test$/123/activate" (extract-argument-value 'test4 test-request arg-resolvers2)))))

(deftest nested-context-test
  (let [test-mw (-> (compojure/context "/merchant" []
                                       (namespace-middleware
                                         (compojure/routes
                                           (compojure/context "/:id/activate" []
                                                              (namespace-middleware
                                                                (compojure/routes
                                                                  (compojure/context "/:key" []
                                                                                     (namespace-middleware
                                                                                       rook-handler 'io.aviso.rook-test3))
                                                                  rook-handler)
                                                                'io.aviso.rook-test2))
                                           rook-handler)
                                         'io.aviso.rook-test))
                    param-handling)
        test-mw2 (-> (namespace-handler "/merchant" 'io.aviso.rook-test
                                        (compojure/GET "/test" [] {:body "test!"})
                                        (namespace-handler "/:id/activate" 'io.aviso.rook-test2
                                                           (namespace-handler "/:key" 'io.aviso.rook-test3)))
                     param-handling)]
    (are [handler method path expected-result]
      (= expected-result
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

      test-mw2 :get "/merchant/test" {:status 200 :headers {} :body "test!"})))