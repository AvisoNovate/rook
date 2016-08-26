(ns sample.request-injection
  (:require [ring.util.response :refer [response]]))

(defn list-all
  "Demonstrates the :request arg resolver, and it's default behavior."
  {:rook-route [:get ""]}
  [^:request request-method]
  (response (name request-method)))

(defn specific-key
  {:rook-route [:get "/specific-key"]}
  [^{:request :path-info} path]
  (response path))

(defn force-failure
  {:rook-route [:get "/failure"]}
  [^:request does-not-exist]
  (response "unreachable"))
