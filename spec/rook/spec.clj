(ns rook.spec
  (:require [speclj.core :refer [describe context it run-specs should= with-all]]
            [io.aviso.rook :refer [gen-table-routes]]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.route.definition.table :as table]))

(defn normalize
  "Normalizes the routes so that they can be compared; the interceptors are replaced
  with their names."
  [routes]
  (mapv #(update % 2 (partial map :name))
        routes))

(defn get-response
  ([routes path]
   (get-response routes path nil))
  ([routes path request]
   (let [interceptors (-> routes
                          (table/table-routes)
                          (route/router :prefix-tree))]
     (-> {:request (merge {:request-method :get}
                          {:path-info path}
                          request)}
         (chain/enqueue [interceptors])
         chain/execute
         :response))))


(describe "io.aviso.rook"

  (context "single, simple namespace"
    (with-all routes (gen-table-routes {"/items" {:ns 'sample.simple}} nil))

    (it "should generate a single route"
        (should= [["/items" :get [:sample.simple/all-items]]]
                 (normalize @routes)))

    (it "can invoke an endpoint"
        (should= :get-item-response
                 (-> @routes
                     (get-response "/items")))))

  (it "can allow a namespace definition to be just a symbol for the namespace"
      (should= [["/items" :get [:sample.simple/all-items]]]
               (normalize (gen-table-routes {"/items" 'sample.simple} nil)))))

(run-specs)
