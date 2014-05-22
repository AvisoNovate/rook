(ns rook.dispatcher-spec
  (:use speclj.core)
  (:require [io.aviso.rook.dispatcher :as dispatcher]
            [io.aviso.rook.client :as client]
            [io.aviso.rook.utils :as utils]
            [io.aviso.rook.async :as rook-async]
            [io.aviso.rook :as rook]
            [ring.mock.request :as mock]
            [clojure.core.async :as async]))


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
            :params-arg)))))

  (describe "loopback-handler"

    (it "should allow resources to collaborate"
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

    (it "should send schema validation failures")

    (it "should return a 500 response if a sync handler throws an exception")))
