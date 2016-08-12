(ns rook.spec
  (:require [speclj.core :refer [describe context it run-specs should= with-all should-throw should
                                 before-all after-all pending]]
            [io.aviso.rook :refer [gen-table-routes]]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http :as server]
            [clj-http.client :as http]
            [sample.static-interceptors :refer [add-elapsed-time]]
            [sample.dynamic-interceptors :refer [endpoint-labeler]])
  (:import [clojure.lang ExceptionInfo]
           [java.net ServerSocket]))

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

(defn execute-request
  [routes path request]
  (let [interceptors (-> routes
                         (table/table-routes)
                         (route/router :prefix-tree))]
    (-> {:request (merge {:request-method :get
                          :path-info path}
                         request)}
        (chain/enqueue [interceptors])
        chain/execute)))

(defn get-response
  ([routes path]
   (get-response routes path nil))
  ([routes path request]
   (:response (execute-request routes path request))))

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
               (normalize (gen-table-routes {"/items" 'sample.simple} nil))))

  (context "request argument resolver"
    (with-all routes (gen-table-routes {"/widgets" 'sample.request-injection} nil))

    (it "defaults the key name to match the parameter symbol"
        (should= {:arg-value :get}
                 (get-response @routes "/widgets")))

    (it "will use an explicit key if provided"
        (should= {:arg-value "/widgets/specific-key"}
                 (get-response @routes "/widgets/specific-key")))

    (it "requires a non-nil value"
        (let [e (try
                  (get-response @routes "/widgets/failure")
                  (catch Throwable t t))]
          (should (instance? ExceptionInfo e))
          (should= "Resolved argument value was nil." (-> e bottom .getMessage))
          (should= {:parameter 'does-not-exist
                    :context-key-path [:request :does-not-exist]}
                   (-> e bottom ex-data)))))

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
                 (get-response @routes "/hotels/123456")))

    (it "can access endpoints in the nested namespace"
        ;; This also demonstrates how nested namespaces inherit constraints from container
        (should= {:hotel-id "111999"
                  :handler :sample.rooms/rooms-index}
                 (get-response @routes "/hotels/111999/rooms")))

    (it "ignores routes where path variables violate constraints"
        (should= nil
                 (get-response @routes "/hotels/no-match"))

        (should= nil
                 (get-response @routes "/hotels/no-match/rooms"))))

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
                   (get-response @routes "/widgets"))))

    (context "static interceptor definitions"
      (with-all routes (gen-table-routes
                         {"/hotels" {:ns 'sample.hotels
                                     :nested {"/:hotel-id/rooms" {:ns 'sample.rooms
                                                                  :interceptors [:elapsed-time]}}}}
                         {:interceptor-defs {:elapsed-time add-elapsed-time}}))

      (it "only apply when added"
          (should= {:handler :sample.hotels/view-hotel
                    :hotel-id "123456"}
                   (get-response @routes "/hotels/123456")))

      (it "affects routes to which it is added"
          ;; This also demonstrates that interceptors in a namespace def apply.
          (should= {:hotel-id "123456"
                    :handler :sample.rooms/rooms-index
                    :headers {"Elapsed-Time" "35"}}
                   (get-response @routes "/hotels/123456/rooms"))))

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
          (should= {:hotel-id "123456"
                    :handler :sample.rooms/rooms-index
                    :headers {"Elapsed-Time" "35"
                              "Endpoint" "sample.rooms/rooms-index"}}
                   (get-response @routes "/hotels/123456/rooms")))))

  (context "core.async"
    (with-all routes (gen-table-routes
                       {"/async/widgets" 'sample.async-widgets
                        "/widgets" 'sample.static-interceptors}
                       {:interceptor-defs {:elapsed-time add-elapsed-time}}))

    (context "end-to-end testing w/ Jetty"

      (with-all port (let [socket (ServerSocket. 0)
                           port (.getLocalPort socket)]
                       (.close socket)
                       (prn :port port)
                       port))

      (with-all server
                (server/create-server {:env :prod
                                       ::server/routes (table/table-routes @routes)
                                       ::server/type :jetty
                                       ::server/port @port
                                       ::server/join? false}))

      (before-all (server/start @server))

      (after-all (server/stop @server))

      (it "can invoke async endpoint"
          (let [response (http/get (str "http://localhost:" @port "/async/widgets/124c41"))]
            (should= {:status 200
                      :body "{:id \"124c41\"}"}
                     (select-keys response [:status :body]))
            (should= {"Elapsed-Time" "35"
                      "Content-Type" "application/edn"}
                     (-> response :headers (select-keys ["Elapsed-Time" "Content-Type"])))))

      (it "can invoke sync endpoint"
          (should= {:status 200
                    :body "[:one :two :three]"}
                   (-> (http/get (str "http://localhost:" @port "/widgets"))
                       (select-keys [:status :body])))))

    (it "will get the response into the context asynchronously"

        (pending "currently tested using end-to-end")

        (should= {:status 200
                    :headers {}
                    :body {:id "async"}}
                   (get-response @routes "/widgets/async")))))

(run-specs)
