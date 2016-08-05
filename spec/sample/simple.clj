(ns sample.simple)

(defn get-item
  {:rook-route [:get "/:id" ]}
  []
  :get-item-response)

(defn ignored-method
  [])
