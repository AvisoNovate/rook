(ns sample.products
  (:require [ring.util.response :refer [response]]))

(def ^:private all-products
  [{:id 1200
    :name "Mouse Rind"}
   {:id 1000
    :name "Lizard Glue"}
   {:id 1100
    :name "Falcon Paste"}])

(defn view-all
  {:rook-route [:get ""]}
  [^:query-param order-by]
  (response {:sort-order order-by
             :products (cond->> all-products
                         order-by (sort-by (keyword order-by)))}))
