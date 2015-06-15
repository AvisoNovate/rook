(ns wildcard)

(defn match-foo
  {:route [:get ["foo"]]}
  []
  :foo)

(defn match-foo-any
  {:route [:get ["foo" :*]]}
  [^:wildcard-path path]
  [:foo-any path])

(defn match-foo-many-bar
  {:route [:get ["foo" "bar" :*]]}
  [wildcard-path]
  [:foo-bar-any wildcard-path])

(defn match-foo-bar
  {:route [:get ["foo" "bar"]]}
  []
  :foo-bar)