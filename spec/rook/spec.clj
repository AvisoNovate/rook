(ns rook.spec
  (:require [speclj.core :refer [describe context it run-specs should= with-all should-throw should
                                 before-all after-all pending]]
            [io.aviso.rook :refer [gen-table-routes]]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http :as http]
            [io.pedestal.test :refer [response-for]]
            [clojure.edn :as edn]
            [clj-http.client :as client]
            [sample.static-interceptors :refer [add-elapsed-time]]
            [sample.dynamic-interceptors :refer [endpoint-labeler]])
  (:import [java.net ServerSocket]))

(defn ^:private stringify-map-values
  [v]
  (cond->> v
    (map? v)
    (reduce-kv (fn [m k v]
                 (assoc m k (str v)))
               {})))

(defn normalize
  "Normalizes the routes so that they can be compared; the interceptors are replaced
  with their names, and regular expressions are replaced with string representation
  (because identical REs compare as false)."
  [routes]
  (mapv (fn [route]
          (as-> route %
                (update % 2 (partial mapv :name))
                (mapv stringify-map-values %)))
        routes))

(defn ^Throwable bottom
  [^Throwable e]
  (if-let [nested (.getCause e)]
    (bottom nested)
    e))

(defn get-response
  [routes path]
  (let [table-routes (table/table-routes routes)
        service-fn (-> {::http/routes table-routes
                        ::http/type :jetty
                        ::http/port 8080}
                       http/create-servlet
                       ::http/service-fn)]
    (response-for service-fn :get (str "http://localhost:8080" path))))

(describe "io.aviso.rook"

  (context "single, simple namespace"
    (with-all routes (gen-table-routes {"/items" {:ns 'sample.simple}} nil))

    (it "should generate a single route"
        (should= [["/items" :get [:sample.simple/all-items]]]
                 (normalize @routes)))

    (it "can invoke an endpoint"
        (should= {:status 200 :body "all-items"}
                 (-> @routes
                     (get-response "/items")
                     (select-keys [:status :body])))))

  (it "can allow a namespace definition to be just a symbol for the namespace"
      (should= [["/items" :get [:sample.simple/all-items]]]
               (normalize (gen-table-routes {"/items" 'sample.simple} nil))))

  (context "request argument resolver"
    (with-all routes (gen-table-routes {"/widgets" 'sample.request-injection} nil))

    (it "defaults the key name to match the parameter symbol"
        (should= {:status 200 :body "get"}
                 (-> @routes
                     (get-response "/widgets")
                     (select-keys [:status :body]))))

    (it "will use an explicit key if provided"
        (should= "/widgets/specific-key"
                 (-> @routes
                     (get-response "/widgets/specific-key")
                     :body)))

    (it "requires a non-nil value"
        (should= {:status 500
                  :body "Internal server error: exception"}
                 (-> @routes
                     (get-response "/widgets/failure")
                     (select-keys [:status :body])))))

  (context "nested namespaces"
    (with-all routes (gen-table-routes
                       {"/hotels" {:ns 'sample.hotels
                                   :nested {"/:hotel-id/rooms" 'sample.rooms}}}
                       nil))

    (it "generates the expected table"
        (should= [["/hotels/:hotel-id" :get [:sample.hotels/view-hotel] :constraints {:hotel-id "\\d{6}"}]
                  ["/hotels/:hotel-id/rooms" :get [:sample.rooms/rooms-index] :constraints {:hotel-id "\\d{6}"}]]
                 (normalize @routes)))

    (it "can access endpoints in outer namespace"
        ;; This also demonstrates getting constraints from the namespace metadata9
        ;; Also, we exercise the :path-param arg resolver.
        (should= {:hotel-id "123456"
                  :handler :sample.hotels/view-hotel}
                 (-> @routes
                     (get-response "/hotels/123456")
                     :body
                     edn/read-string)))

    (it "can access endpoints in the nested namespace"
        ;; This also demonstrates how nested namespaces inherit constraints from container
        (should= {:hotel-id "111999"
                  :handler :sample.rooms/rooms-index}
                 (-> @routes
                     (get-response "/hotels/111999/rooms")
                     :body
                     edn/read-string)))

    (it "ignores routes where path variables violate constraints"
        (should= 404
                 (-> @routes
                     (get-response "/hotels/no-match")
                     :status))

        (should= 404
                 (-> @routes
                     (get-response "/hotels/no-match/rooms")
                     :status))))

  (context "query-params argument resolver"
    (with-all routes (gen-table-routes
                       {"/products" 'sample.products}
                       nil))

    (it "allows query parameter to be omitted (or nil)"
        (should= {:products [1200 1000 1100]
                  :sort-order nil}
                 (-> @routes
                     (get-response "/products")
                     :body
                     edn/read-string
                     (update :products #(map :id %)))))

    (it "provides the query parameter value as the argument"
        (should= {:sort-order "name"
                  :products [1100 1000 1200]}
                 (-> @routes
                     (get-response "/products?order-by=name")
                     :body
                     edn/read-string
                     (update :products #(map :id %))))))

  (context "interceptors"

    (context "static interceptor instances"

      (with-all routes (gen-table-routes
                         {"/widgets" 'sample.static-interceptors}
                         nil))

      (it "generates the expected table"
          (should= [["/widgets" :get [:sample.static-interceptors/add-elapsed-time :sample.static-interceptors/index]]]
                   (normalize @routes)))

      (it "invokes the specified interceptor"
          (should= {:status 200
                    :headers {"Elapsed-Time" "35"}
                    :body [:one :two :three]}
                   (-> @routes
                       (get-response "/widgets")
                       (update :headers select-keys ["Elapsed-Time"])
                       (update :body edn/read-string)))))

    (context "static interceptor definitions"
      (with-all routes (gen-table-routes
                         {"/hotels" {:ns 'sample.hotels
                                     :nested {"/:hotel-id/rooms" {:ns 'sample.rooms
                                                                  :interceptors [:elapsed-time]}}}}
                         {:interceptor-defs {:elapsed-time add-elapsed-time}}))

      (it "only apply when added"
          (should= {:handler :sample.hotels/view-hotel
                    :hotel-id "123456"}
                   (-> @routes
                       (get-response "/hotels/123456")
                       :body
                       edn/read-string)))

      (it "affects routes to which it is added"
          ;; This also demonstrates that interceptors in a namespace def apply.
          (should= {:status 200
                    :body {:hotel-id "123456"
                           :handler :sample.rooms/rooms-index}
                    :headers {"Elapsed-Time" "35"}}
                   (-> @routes
                       (get-response "/hotels/123456/rooms")
                       (update :body edn/read-string)
                       (update :headers select-keys ["Elapsed-Time"])))))

    (context "generated interceptor definitions"
      (with-all routes (gen-table-routes
                         {"/hotels" {:ns 'sample.hotels
                                     :interceptors [:elapsed-time]
                                     :nested {"/:hotel-id/rooms" {:ns 'sample.rooms
                                                                  :interceptors [:labeler]}}}}

                         {:interceptor-defs {:elapsed-time add-elapsed-time
                                             :labeler endpoint-labeler}}))

      (it "generates the expected table"
          ;; This is really to show how inheritance and interceptor order are related
          (should= [["/hotels/:hotel-id" :get [:sample.static-interceptors/add-elapsed-time :sample.hotels/view-hotel] :constraints {:hotel-id "\\d{6}"}]
                    ["/hotels/:hotel-id/rooms" :get [:sample.static-interceptors/add-elapsed-time :sample.dynamic-interceptors/endpoint-labeler :sample.rooms/rooms-index] :constraints {:hotel-id "\\d{6}"}]]
                   (normalize @routes)))

      (it "is invoked for each endpoint"
          (should= {:status 200
                    :body {:hotel-id "123456"
                           :handler :sample.rooms/rooms-index}
                    :headers {"Elapsed-Time" "35"
                              "Endpoint" "sample.rooms/rooms-index"}}
                   (-> @routes
                       (get-response "/hotels/123456/rooms")
                       (update :headers select-keys ["Elapsed-Time" "Endpoint"])
                       (update :body edn/read-string))))))

  (context "core.async"
    (with-all routes (gen-table-routes
                       {"/async/widgets" 'sample.async-widgets
                        "/widgets" 'sample.static-interceptors}
                       {:interceptor-defs {:elapsed-time add-elapsed-time}}))

    (it "will get the response into the context asynchronously"

        (should= {:status 200
                  :body {:id "666333"}}
                 (-> @routes
                     (get-response "/async/widgets/666333")
                     (select-keys [:status :body])
                     (update :body edn/read-string))))

    (context "end-to-end testing w/ Jetty"

      (with-all port (let [socket (ServerSocket. 0)
                           port (.getLocalPort socket)]
                       (.close socket)
                       port))

      (with-all server
                (http/create-server {::http/routes (table/table-routes @routes)
                                     ::http/type :jetty
                                     ::http/port @port
                                     ::http/join? false}))

      (before-all (http/start @server))

      (after-all (http/stop @server))

      (it "can invoke async endpoint"
          (should= {:status 200
                    :headers {"Elapsed-Time" "35"
                              "Content-Type" "application/edn"}
                    :body {:id "124c41"}}
                   (-> (client/get (str "http://localhost:" @port "/async/widgets/124c41")
                                   {:throw-exceptions false})
                       (select-keys [:headers :body :status])
                       (update :headers select-keys ["Elapsed-Time" "Content-Type"])
                       (update :body edn/read-string))))

      (it "can invoke sync endpoint"
          (should= {:status 200
                    :body [:one :two :three]}
                   (-> (client/get (str "http://localhost:" @port "/widgets")
                                   {:throw-exceptions false})
                       (select-keys [:status :body])
                       (update :body edn/read-string)))))))

(run-specs)
