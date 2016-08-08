(ns sample.request-injection)

(defn list-all
  "Demonstrates the :request arg resolver, and it's default behavior."
  {:rook-route [:get ""]}
  [^:request request-method]
  ;; Looks for :request-method in the :request map
  {:arg-value request-method})

(defn specific-key
  {:rook-route [:get "/specific-key"]}
  [^{:request :path-info} path]
  {:arg-value path})

(defn force-failure
  {:rook-route [:get "/failure"]}
  [^:request does-not-exist]
  {:arg-value does-not-exist})
