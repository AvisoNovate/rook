(ns rook.rook-spec
  (:use
    io.aviso.rook
    speclj.core
    clojure.template)
  (:require
    [io.aviso.rook.internals :as internals]
    rook-test
    rook-test2
    rook-test3
    rook-test4
    rook-test5
    rook-test6
    [ring.mock.request :as mock]
    ring.middleware.params
    ring.middleware.keyword-params))


(defn param-handling [handler]
  (-> handler
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(describe "io.aviso.rook"

  (context "argument resolution"

    (it "should use :arg-resolvers to calculate argument values"
        (let [test-mw (-> (namespace-handler ['rook-test])
                          param-handling)]

          (do-template [method path headers expected-result]
            (should= expected-result
                     (-> (mock/request method path)
                         (assoc :headers headers)
                         test-mw))

            :get "/?limit=100" nil {:status 200 :body "limit=100"}

            :get "/123" nil {:status 200 :body "id=123"}

            :post "/123/activate?test1=1test" nil "test1=1test,id=123,test2=,test3=TEST#,test4=test$/123/activate,meth=:post"

            :get "/123/activate" nil nil

            :post "/456/if-modified-since" {"if-modified-since" "time-instant"} {:id "456" :if-modified-since "time-instant"}

            :put "/" nil nil

            :put "/123" nil nil)))

    (it "should expose the request's :params key as an argument"
        (let [handler (namespace-handler ['echo-params])
              params {:foo :bar}]
          (should-be-same params
                          (-> (mock/request :get "/")
                              (assoc :params params)
                              handler
                              :body
                              :params-arg))))

    (it "should expose the request's :params as argument params* with translated keywords"
        (let [handler (namespace-handler ['echo-params])
              params {:user_id "hlship@gmail.com" :new_password "secret"}]
          (should= {:user-id      "hlship@gmail.com"
                    :new-password "secret"}
                   (-> (mock/request :get "/123")
                       (assoc :params params)
                       handler
                       :body))))

    (it "should fail if a map parameter does not include :as"
        (should-throw RuntimeException #_IllegalArgumentException
                      "map argument has no :as key"
                      (let [handler (namespace-handler ['echo-params2])]
                        (-> (mock/request :put "/123")
                            handler)))))

    (context ":injection argument resolution"
      (with handler (-> (namespace-handler [["injection"] 'injection-demo])
                        (wrap-with-injection :data-source "[DS]")))


      (it "should resolve :injection tagged arguments by symbol"

          (let [response (@handler (mock/request :get "/injection"))]
            (should= {:data-source "[DS]"}
                     (:body response)))))

    (context ":resource-uri argument resolver"

      (with handler (wrap-with-standard-middleware
                      (namespace-handler
                        [[] 'creator]
                        [["nested"] 'creator])))
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
                   (internals/resource-uri-for {:server-uri "http://overrride.com"
                                                :context    "/api"})))

      (it "will include the port number if not matching the scheme default"
          (should= "http://server.com:81/"
                   (internals/resource-uri-for {:scheme      :http
                                                :server-port 81
                                                :server-name "server.com"}))

          (should= "https://server.com:232/"
                   (internals/resource-uri-for {:scheme      :https
                                                :server-port 232
                                                :server-name "server.com"})))))


  (run-specs)
