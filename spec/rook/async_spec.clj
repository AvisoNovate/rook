(ns rook.async-spec
  (:use
    [clojure.core.async :only [go chan >!! <! <!! thread]]
    speclj.core)
  (:require
    [io.aviso.rook
     [async :as async]
     [client :as c]
     [utils :as utils]]))

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

  (describe "loopback-handler"

    (it "should expose the loopback in the request"
        (let [fred (fn [request]
                     (go
                       (if (= (:uri request) "/fred")
                         (->
                           (c/new-request (:loopback-handler request))
                           (c/to :get :barney)
                           c/send
                           <! ; get just the :body from the response
                           utils/response)
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
                     :body))))))

(run-specs :color true)
