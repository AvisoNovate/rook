(ns rook.server-spec
  (:use speclj.core)
  (:require [ring.mock.request :as mock]
            [clojure.core.async :refer [go chan >!! <! <!! >! thread timeout close!]]
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
                    :headers {}
                    :body    {:error   "timeout"
                              :message "Processing of request GET /foo/bar timed out after 10 ms."}}
                   response)))

    (it "responds with a timeout immediately if the control channel is closed."
        (let [handler (-> #(-> % :timeout-control-ch close!)
                          (server/wrap-with-timeout 1000))
              start-time (System/currentTimeMillis)
              response (-> (mock/request :get "/fast/timeout")
                           handler
                           <!!)
              elapsed (- (System/currentTimeMillis) start-time)]
          (should (< elapsed 100))

          (should= {:status  HttpServletResponse/SC_GATEWAY_TIMEOUT
                    :headers {}
                    :body    {:error   "timeout"
                              :message "Processing of request GET /fast/timeout timed out."}}
                   response)))

    (it "allows the timeout to be canceled"
        (let [handler (-> (fn [request]
                            (go
                              (-> request :timeout-control-ch (>! :cancel))
                              ;; Sleep past the original timeout
                              (<! (timeout 100))
                              :response-placeholder))
                          (server/wrap-with-timeout 50))
              start-time (System/currentTimeMillis)
              response (-> {}
                           handler
                           <!!)
              elapsed (- (System/currentTimeMillis) start-time)]
          (should (> elapsed 100))
          (should= response :response-placeholder)))))

(run-specs)