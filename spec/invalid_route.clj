(ns invalid-route)

(defn misconfigured
  {:route [:update [:id]]}
  [id])
