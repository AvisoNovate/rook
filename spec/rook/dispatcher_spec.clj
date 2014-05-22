(ns rook.dispatcher-spec
  (:use speclj.core)
  (:require [io.aviso.rook.dispatcher :as dispatcher]
            [ring.mock.request :as mock]))

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

      (let [handler (dispatcher/compile-dispatch-table simple-dispatch-table)
            index-response (handler (mock/request :get "/foo"))
            show-response  (handler (mock/request :get "/foo/1"))]
        (should= {:status 200 :headers {} :body "Hello!"}
          index-response)
        (should= {:status 200 :headers {} :body "Interesting id: 1"}
          show-response)))

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
                        [] 'example.foo `*default-middleware*)))]
        (should= (set simple-dispatch-table) dt)))))
