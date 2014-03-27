(ns io.aviso.rook.internal.map-transform
  "Transform the keys and/or values of a map.")

(defn transform
  "Transforms a map, passing each key through the key-fn and each value through the value-fn."
  [m key-fn value-fn]
  (->
    (reduce-kv (fn [a k v] (assoc! a (key-fn k) (value-fn v)))
               (transient {})
               m)
    persistent!))

(defn transform-keys
  [m key-fn]
  "Transforms just the keys, leaving the values unchanged."
  (transform m key-fn identity))

(defn transform-values
  "Transforms just the values, leaving the keys unchanged."
  [m value-fn]
  (transform m identity value-fn))