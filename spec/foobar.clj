(ns foobar
  {:sync true}
  (:require [ring.util.response :as resp]))

(defn foo
  {:route [:get [:foo-id "foo"]]}
  [foo-id]
  (resp/response (str "foo-id is " foo-id)))

(defn bar
  {:route [:get [:bar-id "bar"]]}
  [bar-id]
  (resp/response (str "bar-id is " bar-id)))
