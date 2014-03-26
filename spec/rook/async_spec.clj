(ns rook.async-spec
  (:use
    [clojure.core.async :only [chan >!! <!! thread]]
    speclj.core)
  (:require
    [io.aviso.rook
     [async :as async]
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
                   <!!)))))

(run-specs :color true)
