(ns rook.dispatcher-spec
  (:use speclj.core
        [clojure.template :only [do-template]])
  (:require [io.aviso.rook.dispatcher :as dispatcher]
            [io.aviso.rook.client :as client]
            [io.aviso.rook.utils :as utils]
            [io.aviso.rook.async :as rook-async]
            [io.aviso.rook :as rook]
            [ring.mock.request :as mock]
            [clojure.core.async :as async]
            ring.middleware.params
            ring.middleware.keyword-params)
  (:import (javax.servlet.http HttpServletResponse)))


(defn namespace-handler
  "Produces a handler based on the given namespace."
  ([ns-sym]
     (dispatcher/compile-dispatch-table
       (dispatcher/namespace-dispatch-table ns-sym)))
  ([context-pathvec ns-sym]
     (dispatcher/compile-dispatch-table
       (dispatcher/namespace-dispatch-table context-pathvec ns-sym)))
  ([context-pathvec ns-sym middleware]
     (dispatcher/compile-dispatch-table
       (dispatcher/namespace-dispatch-table context-pathvec ns-sym middleware)))
  ([options context-pathvec ns-sym middleware]
     (dispatcher/compile-dispatch-table options
       (dispatcher/namespace-dispatch-table context-pathvec ns-sym middleware))))


(defn wrap-with-pprint-request [handler]
  (fn [request]
    (dispatcher/pprint-code request)
    (handler request)))

(defn wrap-with-pprint-response [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (satisfies? clojure.core.async.impl.protocols/ReadPort resp)
        (let [v (async/<!! resp)]
          (async/>!! resp v)
          (dispatcher/pprint-code v))
        (dispatcher/pprint-code resp))
      (prn)
      resp)))


(defn wrap-with-resolve-method [handler]
  (rook/wrap-with-arg-resolvers handler
    (fn [kw request]
      (if (identical? kw :request-method)
        (:request-method request)))))


(create-ns 'example.foo)

(binding [*ns* (the-ns 'example.foo)]
  (eval '(do
           (clojure.core/refer-clojure)
           (require '[ring.util.response :as resp])
           (defn index []
             (resp/response "Hello!"))
           (defn show [id]
             (resp/response (str "Interesting id: " id)))
           (defn create [x]
             (resp/response (str "Created " x))))))

(def ^:dynamic *default-middleware* identity)

(def simple-dispatch-table
  [[:get  ["foo"]     'example.foo/index  `*default-middleware*]
   [:post ["foo"]     'example.foo/create `*default-middleware*]
   [:get  ["foo" :id] 'example.foo/show   `*default-middleware*]])

(describe "io.aviso.rook.dispatcher"

  (describe "unnest-dispatch-table"

    (it "should leave tables with no nesting unchanged"

      (should= simple-dispatch-table
        (dispatcher/unnest-dispatch-table simple-dispatch-table)))

    (it "should correctly unnest DTs WITHOUT default middleware"

      (let [dt [(into [["api"]] simple-dispatch-table)]]
        (should= [[:get  ["api" "foo"]     'example.foo/index  `*default-middleware*]
                  [:post ["api" "foo"]     'example.foo/create `*default-middleware*]
                  [:get  ["api" "foo" :id] 'example.foo/show   `*default-middleware*]]
          (dispatcher/unnest-dispatch-table dt))))

    (it "should correctly unnest DTs WITH default middleware and empty context pathvec"

      (let [dt [(into [[] `*default-middleware*]
                  (mapv pop simple-dispatch-table))]]
        (should= simple-dispatch-table
          (dispatcher/unnest-dispatch-table dt))))

    (it "should correctly unnest DTs WITH default middleware and non-empty context pathvec"

      (let [dt [(into [["api"] `*default-middleware*]
                  (mapv pop simple-dispatch-table))]]
        (should= [[:get  ["api" "foo"]     'example.foo/index  `*default-middleware*]
                  [:post ["api" "foo"]     'example.foo/create `*default-middleware*]
                  [:get  ["api" "foo" :id] 'example.foo/show   `*default-middleware*]]
          (dispatcher/unnest-dispatch-table dt)))))

  (describe "compile-dispatch-table"

    (it "should produce a handler returning valid response maps"

      (let [handler (dispatcher/compile-dispatch-table
                      (mapv (comp #(conj % rook/wrap-with-default-arg-resolvers) pop)
                        simple-dispatch-table))
            index-response  (handler (mock/request :get "/foo"))
            show-response   (handler (mock/request :get "/foo/1"))
            create-response (handler (merge (mock/request :post "/foo")
                                       {:params {:x 123}}))]
        (should= {:status 200 :headers {} :body "Hello!"}
          index-response)
        (should= {:status 200 :headers {} :body "Interesting id: 1"}
          show-response)
        (should= {:status 200 :headers {} :body "Created 123"}
          create-response)))

    (it "should inject the middleware"

      (let [handler (dispatcher/compile-dispatch-table simple-dispatch-table)
            a (atom 0)]
        (binding [*default-middleware* (fn [handler]
                                         (fn [request]
                                           (swap! a inc)
                                           (handler request)))]
          (handler (mock/request :get "/foo"))
          (should= 1 @a)))))

  (describe "namespace-dispatch-table"

    (it "should return a DT reflecting the state of the namespace"

      (let [dt (set (dispatcher/unnest-dispatch-table
                      (dispatcher/namespace-dispatch-table
                        ["foo"] 'example.foo `*default-middleware*)))]
        (should= (set simple-dispatch-table) dt))))

  (describe "compiled handlers"

    (it "should return the expected responses"
      (do-template [method path namespace-name extra-params expected-value]
        (should= expected-value
          (let [mw (fn [handler]
                     (-> handler
                       rook/wrap-with-default-arg-resolvers
                       wrap-with-resolve-method
                       ring.middleware.keyword-params/wrap-keyword-params
                       ring.middleware.params/wrap-params))
                dt (dispatcher/namespace-dispatch-table
                     [] namespace-name mw)
                handler (dispatcher/compile-dispatch-table dt)
                body    #(:body % %)]
            (-> (mock/request method path)
              (update-in [:params] merge extra-params)
              handler
              ;; TODO: fix rook-spec and rook-test/activate (the
              ;; latter should return a response map rather than a
              ;; string) and switch back to :body
              body)))

        :get "/?limit=100"   'rook-test {} "limit=100"
        :get "/"             'rook-test {} "limit="
        :get "/123"          'rook-test {} "id=123"
        :get "/123/activate" 'rook-test {} nil
        :put "/"             'rook-test {} nil
        :put "/123"          'rook-test {} nil

        :post "/123/activate" 'rook-test
        {:test1 "foo" :test2 "bar" :test3 "baz" :test4 "quux"}
        "test1=foo,id=123,test2=bar,test3=baz,test4=quux,request=13,meth=:post")))

  (describe "async handlers"

    (it "should return a channel with the correct response"

      (let [handler (namespace-handler
                      {:apply-middleware-fn dispatcher/apply-middleware-async}
                      [] 'barney `*default-middleware*)]
        (should= {:message "ribs!"}
          (-> (mock/request :get "/") handler async/<!! :body))))

    (it "should expose the request's :params key as an argument"
      (let [handler (namespace-handler
                      {:apply-middleware-fn dispatcher/apply-middleware-async}
                      [] 'echo-params rook/wrap-with-default-arg-resolvers)
            params {:foo :bar}]
        (should-be-same params
          (-> (mock/request :get "/")
            (assoc :params params)
            handler
            async/<!!
            :body
            :params-arg))))

    (it "should return a 500 response if a sync handler throws an exception"
      (let [handler (rook-async/async-handler->ring-handler
                      (rook-async/wrap-with-loopback
                        (namespace-handler ["fail"] 'failing rook-async/wrap-restful-format)))]
          (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR
            (-> (mock/request :get "/fail") handler :status)))))

  (describe "loopback-handler"

    (it "should allow two resources to collaborate"
      (let [handler (rook-async/async-handler->ring-handler
                      (rook-async/wrap-with-loopback
                        (dispatcher/compile-dispatch-table
                          {:apply-middleware-fn dispatcher/apply-middleware-async}
                          (into
                            (dispatcher/namespace-dispatch-table
                              ["fred"] 'fred rook/wrap-with-default-arg-resolvers)
                            (dispatcher/namespace-dispatch-table
                              ["barney"] 'barney rook/wrap-with-default-arg-resolvers)))))]
        (should= ":barney says `ribs!'"
          (-> (mock/request :get "/fred")
            handler
            :body
            :message))))

    (it "should allow three resources to collaborate"
      (let [handler (rook-async/async-handler->ring-handler
                      (rook-async/wrap-with-loopback
                        (dispatcher/compile-dispatch-table
                          {:apply-middleware-fn dispatcher/apply-middleware-async}
                          (-> (dispatcher/namespace-dispatch-table
                                ["fred"] 'fred rook/wrap-with-default-arg-resolvers)
                            (into
                              (dispatcher/namespace-dispatch-table
                                ["barney"] 'barney rook/wrap-with-default-arg-resolvers))
                            (into
                              (dispatcher/namespace-dispatch-table
                                ["betty"] 'betty rook/wrap-with-default-arg-resolvers))))))]
        (should= ":barney says `:betty says `123 is a very fine id!''"
          (-> (mock/request :get "/fred/123") handler :body :message)))))

  (describe "handlers with schema attached"

    (it "should respond appropriately given a valid request"
      (let [middleware (fn [handler]
                         (-> handler
                           rook-async/wrap-with-schema-validation
                           rook/wrap-with-default-arg-resolvers))
            handler    (->> (dispatcher/namespace-dispatch-table
                              ["validating"] 'validating middleware)
                         (dispatcher/compile-dispatch-table
                           {:apply-middleware-fn dispatcher/apply-middleware-async})
                         rook-async/wrap-with-loopback
                         rook-async/async-handler->ring-handler)
            response   (-> (mock/request :post "/validating")
                         (merge {:params {:name "Vincent"}})
                         handler)]
        (should= HttpServletResponse/SC_OK (:status response))
        (should= [:name] (:body response))))

    (it "should send schema validation failures"
      (let [middleware (fn [handler]
                         (-> handler
                           rook-async/wrap-with-schema-validation
                           ring.middleware.keyword-params/wrap-keyword-params
                           ring.middleware.params/wrap-params))
            handler    (->> (dispatcher/namespace-dispatch-table
                              ["validating"] 'validating middleware)
                         (dispatcher/compile-dispatch-table
                           {:apply-middleware-fn dispatcher/apply-middleware-async})
                         rook-async/wrap-with-loopback
                         rook-async/async-handler->ring-handler)
            response   (-> (mock/request :post "/validating")
                         handler)]
        (should= HttpServletResponse/SC_BAD_REQUEST (:status response))
        (should= "validation-error" (-> response :body :error))
        ;; TODO: Not sure that's the exact format I want sent back to the client!
        (should= "{:name missing-required-key}" (-> response :body :failures))))))
