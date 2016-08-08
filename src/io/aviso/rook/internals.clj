(ns io.aviso.rook.internals
  "Internal utilities subject to change at any time and not for reuse."
  {:no-doc true})

(declare deep-merge)

(defn ^:private merge-values [left right]
  (cond
    (map? left)
    (deep-merge left right)

    (vector? left)
    (into left right)

    (seq? left)
    (concat left right)

    :else
    right))

(defn deep-merge
  "Merges two or more maps, recursively."
  [left right & more-maps]
  (apply merge-with merge-values left right more-maps))

(defn to-message
  [^Throwable t]
  (or (.getMessage t)
      (-> t .getClass .getName)))

(defn into+
  [coll & other-colls]
  (reduce into coll other-colls))
