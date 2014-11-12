(ns integration-spec
  (:use speclj.core)
  (:require
    [org.example.server :as server]
    [clj-http.client :as client]))

(describe "integration"
  (with-all server (server/start-server 8080))

  ;; Force the server to start up
  (before-all @server)

  ;; And shut it down at the very end
  (after-all
    ;; start-server returns a function to stop the server, invoke it after all characteristics
    ;; have executed.
    (@server))

  (it "can get current counters"

      (let [response (client/get "http://localhost:8080/counters"
                                 {:accept :edn})]
        (->> response
            :body
            read-string
            (should= {"foo" 0 "bar" 0})))))

(run-specs)