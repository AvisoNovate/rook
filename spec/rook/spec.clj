(ns rook.spec
  (:require [speclj.core :refer [describe context it run-specs should= with-all]]
            [io.aviso.rook :refer [gen-table-routes]]))

(defn normalize [routes]
  (mapv #(update % 2 (partial map :name))
        routes))

(describe "io.aviso.rook"

  (context "single, simple namespace"
    (with-all routes (gen-table-routes {"/item" {:ns 'sample.simple}} nil))

    (it "should generate a single route"
        (should= [["/item/:id" :get [:sample.simple/get-item]]]
                 (normalize @routes)))))

(run-specs)
