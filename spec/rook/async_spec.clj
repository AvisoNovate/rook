(ns rook.async-spec
  (:use
    [clojure.core.async :only [go chan >!! <! <!! thread]]
    speclj.core)
  (:require
    [ring.mock.request :as mock]
    [io.aviso.rook :as rook]
    [io.aviso.rook
     [async :as async]
     [client :as c]
     [utils :as utils]]))

(defn- invoke [handler] (handler {}))

(describe "io.aviso.rook.async"

  (describe "routing"

    (it "return the first result that is not false"

        (let [req {:uri "whatever"}]
          (should= req
                   (->
                     (async/routing req
                                    (fn [_] (thread false))
                                    (fn [request] (thread
                                                    (utils/response 200 request))))
                     <!!
                     :body))))

    (it "returns false when all handlers return false"

        (should= false
                 (->
                   (async/routing {} (fn [_] (thread false)))
                   <!!)))

    (it "returns false when there are no handlers"
        (should= false
                 (->
                   (async/routing {})
                   <!!))))

  (describe "result->channel"

    (it "returns most values normally"
        (should= :anything
                 (-> :anything
                     async/result->channel
                     <!!)))

    (it "converts nil to false"

        (should= false
                 (-> nil
                     async/result->channel
                     <!!))))

  (describe "ring-handler->async-handler"

    (it "executes on a different thread"

        (should-not-be-same (Thread/currentThread)
                            (->
                              (fn [_] (Thread/currentThread))
                              async/ring-handler->async-handler
                              invoke
                              <!!)))

    (it "converts nil to false"
        (should= false
                 (->
                   (constantly nil)
                   async/ring-handler->async-handler
                   invoke
                   <!!))))


  (describe "namespace-handler"

    (it "allows the path to be nil"
        (let [handler (async/namespace-handler 'barney)]
          (should= {:message "ribs!"}
                   (->
                     (mock/request :get "/")
                     handler
                     <!!
                     :body))))

    (it "should expose the request's :params key as an argument"
        (let [handler (->
                        (async/namespace-handler 'echo-params)
                        (rook/wrap-with-default-arg-resolvers))
              params {:foo :bar}]
          (should-be-same params
            (->
              (mock/request :get "/")
              (assoc :params params)
              handler
              <!!
              :body
              :params-arg)))))


  (describe "loopback-handler"

    (it "should expose the loopback in the request"
        (let [fred (fn [request]
                     (go
                       (if (= (:uri request) "/fred")
                         (->
                           (c/new-request (:loopback-handler request))
                           (c/to :get :barney)
                           c/send
                           (c/then (reply (utils/response reply))))
                         false)))
              barney (fn [request]
                       (go
                         (if (= (:uri request) "/barney")
                           (utils/response 200 "rubble")
                           false)))
              wrapped (async/wrap-with-loopback (async/routes fred barney))]
          (should= "rubble"
                   (->
                     {:uri "/fred"}
                     wrapped
                     <!!
                     :body)))))

  (describe "end-to-end test"

    (it "should allow resources to collaborate"

        (let [routes (async/routes
                       (async/namespace-handler "/fred" 'fred)
                       (async/namespace-handler "/barney" 'barney))
              handler (->
                        routes
                        async/wrap-with-loopback
                        async/async-handler->ring-handler)]
          (should= ":barney says `ribs!'"
                   (-> (mock/request :get "/fred")
                       handler
                       :body
                       :message))))))

(run-specs)
