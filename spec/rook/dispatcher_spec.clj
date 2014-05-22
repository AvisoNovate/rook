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

(def ^:dynamic *default-pipeline* identity)

(def simple-dispatch-table
  [[:get  ["foo"]     'example.foo/index  `*default-pipeline*]
   [:post ["foo"]     'example.foo/create `*default-pipeline*]
   [:get  ["foo" :id] 'example.foo/show   `*default-pipeline*]])

(describe "io.aviso.rook.dispatcher"

  (describe "unnest-dispatch-table"

    (it "should leave tables with no nesting unchanged"

      (should= simple-dispatch-table
        (dispatcher/unnest-dispatch-table simple-dispatch-table)))

    (it "should correctly unnest DTs WITHOUT a default pipeline"

      (let [dt [(into [["api"]] simple-dispatch-table)]]
        (should= [[:get  ["api" "foo"]     'example.foo/index  `*default-pipeline*]
                  [:post ["api" "foo"]     'example.foo/create `*default-pipeline*]
                  [:get  ["api" "foo" :id] 'example.foo/show   `*default-pipeline*]]
          (dispatcher/unnest-dispatch-table dt))))

    (it "should correctly unnest DTs WITH a default pipeline and empty context pathvec"

      (let [dt [(into [[] `*default-pipeline*]
                  (mapv pop simple-dispatch-table))]]
        (should= simple-dispatch-table
          (dispatcher/unnest-dispatch-table dt))))

    (it "should correctly unnest DTs WITH a default pipeline and non-empty context pathvec"

      (let [dt [(into [["api"] `*default-pipeline*]
                  (mapv pop simple-dispatch-table))]]
        (should= [[:get  ["api" "foo"]     'example.foo/index  `*default-pipeline*]
                  [:post ["api" "foo"]     'example.foo/create `*default-pipeline*]
                  [:get  ["api" "foo" :id] 'example.foo/show   `*default-pipeline*]]
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

    (it "should inject the pipeline"

      (let [handler (dispatcher/compile-dispatch-table simple-dispatch-table)
            a (atom 0)]
        (binding [*default-pipeline* (fn [handler]
                                       (fn [request]
                                         (swap! a inc)
                                         (handler request)))]
          (handler (mock/request :get "/foo"))
          (should= 1 @a)))))

  (describe "namespace-dispatch-table"

    (it "should return a DT reflecting the state of the namespace"

      (let [dt (set (dispatcher/unnest-dispatch-table
                      (dispatcher/namespace-dispatch-table
                        [] 'example.foo `*default-pipeline*)))]
        (should= (set simple-dispatch-table) dt)))))
