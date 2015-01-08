(ns rook.server-spec
  (:use speclj.core)
  (:require [ring.mock.request :as mock]
            [clojure.core.async :refer [go chan >!! <! <!! thread timeout]]
            [io.aviso.rook.server :as server])
  (:import [javax.servlet.http HttpServletResponse]))

(defn- respond-after
  [timeout-ms response]
  (fn [_]
    (go
      (<! (timeout timeout-ms))
      response)))

(describe "io.aviso.rook.server"
  (context "wrap-with-timeout"

    (it "provides handler's response if before the timeout"
        (let [handler (-> (respond-after 100 :response-placeholder)
                          (server/wrap-with-timeout 1000))
              response (-> {}
                           handler
                           <!!)]
          (should= response :response-placeholder)))

    (it "responds with a 504 if handler response is too slow"
        (let [handler (-> (respond-after 100 :does-not-matter)
                          (server/wrap-with-timeout 10))
              response (-> (mock/request :get "/foo/bar")
                           handler
                           <!!)]
          (should= {:status  HttpServletResponse/SC_GATEWAY_TIMEOUT
                    :headers {"Content-Type" "text/plain"}
                    :body    "Processing of request GET /foo/bar timed out after 10 ms."}
                   response)))))

(run-specs)