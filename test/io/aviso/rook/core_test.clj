(ns io.aviso.rook.core-test
  (:require [ring.mock.request :as mock]
            [ring.middleware.params]
            [ring.middleware.keyword-params]
            [compojure.core :as compojure])
  (:use io.aviso.rook.core
        clojure.test))

(defn index [limit]
  {:status 200
   :body   (str "limit=" limit)})

(defn show [id]
  {:status 200
   :body   (str "id=" id)})

(defn
  ^{:path-spec [:post "/:id/activate"]}
  activate [test1 id request test2 test3 test4 request-method]
  (str "test1=" test1
       ",id=" id
       ",test2=" test2,
       ",test3=" test3,
       ",test4=" test4,
       ",request=" (count request)
       ",meth=" request-method))

(defn mkrequest [method path]
  ((-> identity
       ring.middleware.keyword-params/wrap-keyword-params
       ring.middleware.params/wrap-params)
   (mock/request method path)))

(defn u-show [id]
  (str "!" id "!"))

(ns io.aviso.rook.core-test2)

(defn index [offset id]
  {:body (str "id=" id "&offset=" offset)})

(ns io.aviso.rook.core-test3)

(defn index [id key]
  {:body (str "test3,id=" id ",key=" key)})

(in-ns 'io.aviso.rook.core-test)

(deftest namespace-scanning-middleware-test
  (let [test-mw (is (namespace-scanning-middleware (fn [request] ((:test-fun request) request)) *ns*))
             test-mw2 (is (namespace-scanning-middleware (fn [request] ((:test-fun request) request)) 'io.aviso.rook.core-test2))]
    (is (= {:arg-resolvers nil :metadata (meta #'io.aviso.rook.core-test/index) :function #'io.aviso.rook.core-test/index
             :namespace *ns*}
            (test-mw (assoc (mkrequest :get "/?limit=100") :test-fun :rook))))
    (is (= {:arg-resolvers nil :metadata (meta #'io.aviso.rook.core-test/show) :function #'io.aviso.rook.core-test/show
             :namespace *ns*}
            (test-mw (assoc (mkrequest :get "/123") :test-fun :rook))))
    (is (= {:arg-resolvers nil :metadata (meta #'io.aviso.rook.core-test/activate) :function #'io.aviso.rook.core-test/activate
             :namespace *ns*}
            (test-mw (assoc (mkrequest :post "/123/activate") :test-fun :rook))))
    (is (nil? (test-mw (assoc (mkrequest :get "/123/activate") :test-fun :rook))))
    (is (nil? (test-mw (assoc (mkrequest :put "/") :test-fun :rook))))
    (is (nil? (test-mw (assoc (mkrequest :put "/123") :test-fun :rook))))
    (is (= {:arg-resolvers nil
            :metadata (meta #'io.aviso.rook.core-test2/index)
            :function #'io.aviso.rook.core-test2/index
            :namespace 'io.aviso.rook.core-test2}
           (test-mw2 (assoc (mkrequest :get "/?offset=100") :test-fun :rook))))))

(deftest namespace-handler-test
  (when-let [test-mw (is (namespace-scanning-middleware rook-handler *ns*))]
    (is (= {:status 200 :body "limit=100"}
            (test-mw (mkrequest :get "/?limit=100"))))
    (is (= {:status 200 :body "id=123"}
            (test-mw (mkrequest :get "/123"))))
    (is (= "test1=1test,id=123,test2=,test3=,test4=,request=13,meth="
          (test-mw (mkrequest :post "/123/activate?test1=1test"))))
    (is (nil? (test-mw (assoc (mkrequest :get "/123/activate") :test-fun :rook))))
    (is (nil? (test-mw (assoc (mkrequest :put "/") :test-fun :rook))))
    (is (nil? (test-mw (assoc (mkrequest :put "/123") :test-fun :rook))))))


(deftest complete-handler-test
  (when-let [test-mw (is (namespace-scanning-middleware
                           (arg-resolver-middleware rook-handler
                                                    (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
                                                    (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request))))
                                                    #'request-arg-resolver)
                           *ns*))]
    (is (= "test1=TEST!,id=123,test2=TEST@,test3=TEST#,test4=test$/123/activate,request=13,meth=:1234"
          (test-mw (mkrequest :post "/123/activate?test1=1test"))))))

(deftest arg-resolver-test
  (let [map-resolver (build-map-arg-resolver :test1 "TEST!" :test2 "TEST@" :test3 "TEST#" :request-method :1234)
        fn-resolver  (build-fn-arg-resolver :test4 (fn [request] (str "test$" (:uri request))))
        arg-resolvers1 [map-resolver fn-resolver #'request-arg-resolver]
        arg-resolvers2 [#'request-arg-resolver map-resolver fn-resolver]
        test-request (mkrequest :post "/123/activate")]
    (is (= "TEST!" (extract-argument-value 'test1 test-request [map-resolver])))
    (is (= "TEST@" (extract-argument-value 'test2 test-request [map-resolver])))
    (is (= "TEST#" (extract-argument-value 'test3 test-request [map-resolver])))
    (is (= "TEST!" (extract-argument-value 'test1 test-request arg-resolvers1)))
    (is (= "TEST@" (extract-argument-value 'test2 test-request arg-resolvers1)))
    (is (= "TEST#" (extract-argument-value 'test3 test-request arg-resolvers1)))
    (is (nil? (extract-argument-value 'test1 test-request [fn-resolver])))
    (is (nil? (extract-argument-value 'test2 test-request [fn-resolver])))
    (is (nil? (extract-argument-value 'test3 test-request [fn-resolver])))
    (is (= :1234 (extract-argument-value 'request-method test-request [map-resolver])))
    (is (= :1234 (extract-argument-value 'request-method test-request arg-resolvers1)))
    (is (= :post (extract-argument-value 'request-method test-request arg-resolvers2)))
    (is (= "test$/123/activate" (extract-argument-value 'test4 test-request arg-resolvers2)))
    ))

(deftest nested-context-test
  (let [test-mw (is (compojure/context "/merchant" []
                                       (namespace-scanning-middleware
                                         (compojure/routes
                                           (compojure/context "/:id/activate" []
                                                              (namespace-scanning-middleware
                                                                (compojure/routes
                                                                  (compojure/context "/:key" []
                                                                    (namespace-scanning-middleware
                                                                      rook-handler 'io.aviso.rook.core-test3))
                                                                  rook-handler)
                                                                  'io.aviso.rook.core-test2))
                                           rook-handler)
                                         *ns*)))
        test-mw2 (is (rook-middleware "/merchant" *ns*
                       (compojure/GET "/test" [] {:body "test!"})
                       (rook-middleware "/:id/activate" 'io.aviso.rook.core-test2
                         (rook-middleware "/:key" 'io.aviso.rook.core-test3))))
        ]
    (is (nil? (test-mw (mkrequest :post "/456/activate"))))
    (is (= {:status 200 :body "limit="} (test-mw (mkrequest :get "/merchant/"))))
    (is (= {:status 200 :body "id=6789"} (test-mw (mkrequest :get "/merchant/6789"))))
    (is (= {:body "id=4567&offset=1234"} (test-mw (mkrequest :get "/merchant/4567/activate?offset=1234"))))
    (is (= {:body "test3,id=4567,key=test_key"} (test-mw (mkrequest :get "/merchant/4567/activate/test_key"))))
    (is (nil? (test-mw2 (mkrequest :post "/456/activate"))))
    (is (= {:status 200 :body "limit="} (test-mw2 (mkrequest :get "/merchant/"))))
    (is (= {:status 200 :body "id=6789"} (test-mw2 (mkrequest :get "/merchant/6789"))))
    (is (= {:body "id=4567&offset=1234"} (test-mw2 (mkrequest :get "/merchant/4567/activate?offset=1234"))))
    (is (= {:body "test3,id=4567,key=test_key"} (test-mw2 (mkrequest :get "/merchant/4567/activate/test_key"))))
    (is (= {:status 200 :headers {} :body "test!"} (test-mw2 (mkrequest :get "/merchant/test"))))

    )
  )

(run-tests)