(ns io.aviso.rook.core
  (:require
    [compojure.core :as compojure]
    [clout.core :as clout]
    [clojure.core.memoize :as memo]
    [clojure.walk :as walk]
    [clojure.pprint :as pp]))

(def
  DEFAULT-MAPPINGS
  "Default mappings for route specs to functions. We use keyword for function name for increased readability."
  [[[:get "/new"] :new]
   [[:get "/:id"] :show]
   [[:put "/:id"] :update]
   [[:patch "/:id"] :update]
   [[:get "/:id/edit"] :edit]
   [[:delete "/:id"] :delete]
   [[:get "/"] :index]
   [[:post "/"] :create]])

(defn symbol-for-function?
  "Checks if a symbol is actually a function. The same function exists in clojure.test, but we don't want the dependency
  on clojure.test in live, running code, do we?"
  [sym]
  (-> sym
      deref
      ifn?))

(defn extract-argument-value
  "Return parameter values for handler function based on request data. The order of parameter resolution is following:
  => request parameter gets mapped to the request
  => data parameter gets mapped to the parsed and validated request data (if available)
  => parameters found in (:route-params request) are mapped then
  => then we use POST/GET parameters from (:params) (we assume that they are keywordized using appropriate middleware)"
  [argument request arg-resolvers]
  (let [arg-kw (keyword (name argument))
        api-kw (keyword (.replace (name argument) "-" "_"))]
    (or
      (loop [[arg-resolver & arg-resolvers] arg-resolvers]
        (when arg-resolver
          (or
            (arg-resolver arg-kw request)
            (recur arg-resolvers))))
      (when (= :request arg-kw) request)
      (get (:route-params request) api-kw)
      (get (:params request) api-kw))))

(defn- ns-function
  "Return the var for the given namespance and function keyword."
  [namespace function-key]
  (when-let [f (ns-resolve namespace (symbol (name function-key)))]
    (when (symbol-for-function? f) ;it has to be a function all right
      f)))

;(defn- ns-validation
;  "Return the validation for the given namespance and action keyword (:create, :update).
;   Disables :required validations for :update actions"
;  [namespace action]
;  (when-let [v (ns-resolve namespace (symbol "validation"))]
;    (condp = action
;      :create (deref v)
;      :update (walk/postwalk-replace {[:required true] [:required false]} (deref v)))))

;(defn route-function-wrapper
;  [processors arg-resolvers namespace f]
;  (fn [request]
;    (if-let [processor (first processors)]
;      ((mw namespace (meta f) (processors-wrapper (rest processors) arg-resolvers namespace f))
;        request)
;      (invoke-handler-function f (-> f meta :arglists first)  request))))

;(defn create-function-route-handler
;  "Create a handler for a route which invokes function <f> after parsing and validation of request data (if :validate? metadata is true)
;   The function is invoked with arguments as determined by extract-param-value.
;   The request is augmented with an :env entry containing a map of :mode, :service and :services."
;  [namespace f default-middlewares default-arg-resolvers]
;  (let [{:keys [middlewares arg-resolvers arglists] :as meta-data} (meta f)]
;    (fn [request]
;      (let [request (assoc request
;                      :rook {:namespace namespace
;                             :function f
;                             :metadata meta-data
;                             :arg-resolvers (concat default-arg-resolvers arg-resolvers)})
;            rook-wrapper (route-function-wrapper (concat default-middlewares middlewares))]
;        (rook-wrapper request)))))

;    (fn [request]
;      (l/debugf "Request %s: %s" (:request-method request) (:uri request))
;          (v/validate-and-process request validation
                                  ; Add data (transformed and validated) as another option for params
;                                  (partial invoke-handler-function f (first arglists) request))
;          (invoke-handler-function f (first arglists) request nil))))))

(defn- success?
  [{:keys [status]}]
  (<= 200 status 399))

;(defn wrap-auth
;  "Ring middleware which authorizes the request by calling <auth-fn> with the request and <permissions> (a collections of permission strings or a function of the request, returning same)
;    * <handler> is called if auth is successful (auth-fn returns a 2xx status response)
;    * Otherwise the response from the auth-fn is returned as-is
;   If <auth-fn> is nil then handler is always invoked."
;  [handler auth-fn permissions]
;  (fn [request]
;    (if auth-fn
;      (let [permissions (if (fn? permissions) (permissions request) permissions)
;            auth-response (auth-fn request permissions)]
;        (if (success? auth-response)
;          (handler request)
;          auth-response))
;      (handler request))))

;(defn make-function-route
;  "Create function route from route-mapping and namespace."
;  [namespace [path-spec function-key] default-processors default-arg-resolvers]
;  (when-let [f (ns-function namespace function-key)]
;    (let [[method path] path-spec
;;          permissions (:permissions (meta f))
;;          auth (or (:auth (meta f)) :default)
;;          auth-fn (get auths auth)
;          handler (create-function-route-handler namespace f default-processors default-arg-resolvers)]
;;      (l/infof "Building route for %s with %s" [path-spec function-key] (l/pprint-str (select-keys (meta f) [:auth :permissions :path-spec :name])))
;      (compojure/make-route method path
;;                                 (wrap-auth handler auth-fn permissions)
;                                 handler
;                                 ))))


(defn function-entry
  "Create function entry if it has :path-spec defined in its metadata, for example:

  (defn
   ^{:path-spec [:post \"/:id/activate\"]}
   activate [id]
   ...
   )"
  [sym]
  (let [symbol-meta (meta sym)]
    (when-let [path-spec (:path-spec symbol-meta)]
      [path-spec (keyword (:name symbol-meta))])))

(defn ns-paths
  "Returns paths for <namespace> using DEFAULT_MAPPINGS and by scanning for functions :path-spec metadata.
   Each path returned is a tuple of [path-spec function-key] where:
     path-spec is a tuple of [method path]
     function-key is a a keyword with the same name as a function in <namespace>"
  [namespace]
  (concat
    (remove nil?
            (map function-entry
                 (filter symbol-for-function? (map second (ns-publics namespace)))))
    DEFAULT-MAPPINGS))

;(defn- scan-namespace
;  "Build a seq of routes for namespace by adding custom routes (for fns with :path-spec metadata) to DEFAULT_MAPPINGS"
;  [namespace]
;  (let [paths (ns-paths namespace)]
;    (l/infof "Setting route mappings for %s to %s" namespace (l/pprint-str paths))
;    (remove nil?
;            (map #(make-function-route namespace auths env service services %) (ns-paths namespace)))))

;cache seems unnecessary as we build the list once per handler
;(def scan-namespace (memo/memo scan-namespace-uncached))

(defn get-function-meta
  "Get meta for route-mapping and namespace."
  [namespace [path-spec function-key]]
  (when-let [f (ns-function namespace function-key)]
    (let [[method path] path-spec]
      (assoc (meta f) :method method :path path))))

(defn- scan-namespace-for-doc
  "Build a map of functions in namespace <-> url prefixes."
  [namespace]
  (remove nil?
          (map #(get-function-meta namespace %) (ns-paths namespace))))

;(defn describe-context [context-path [path args namespace & contexts]]
;  (let [new-context-path (str context-path path)
;        handlers (scan-namespace-for-doc namespace)]
;    (cons
;      (desc/resource new-context-path namespace (ns-validation namespace) handlers)
;      (map (partial describe-context new-context-path) contexts))))
;
;(defn doc-route-uncached [service contexts]
;  (compojure/make-route :get "/"
;                             (fn [request]
;                               (let [html (desc/resource-page service
;                                                              (map (partial describe-context "") contexts))]
;                                 {:body html}))))
;
;(def doc-route (memo/memo doc-route-uncached))
;
;;if we want to namespace-specific for the cache, we will require a newer version of clojure.core.memoize
;(defn clear-namespace-cache!
;  "Clear namespace cache, forcing a re-scan of every namespace checked."
;  []
;  (memo/memo-clear! scan-namespace)
;  (memo/memo-clear! doc-route))

;(defn make-namespace-handler
;  "Create a namespace handler, which will scan a namespace (cached) for functions matching the naming convention.
;   <auths> should be a map of auth types to functions of request and permissions returning a ring response map."
;  [namespace auths env service services]
;  (fn [request]
;    (apply compojure/routing request (scan-namespace namespace auths env service services))))
;
;(defn make-doc-handler
;  "Create a namespace documention handler, which will scan namespaces (cached) for functions matching the naming convention."
;  [service & contexts]
;  (fn [request]
;    (compojure/routing request (doc-route service contexts))))
;
;(defn resources [env service services auths contexts]
;  (when (seq contexts)
;    (doall (map (fn [[path args namespace & contexts]]
;                  `(compojure/context ~path ~args
;                                 (make-namespace-handler ~namespace ~auths ~env ~service ~services)
;                                 ~@(resources env service services auths contexts)))
;                contexts))))
;
;(defmacro routes
;  "Creates compojure routes for the given <contexts>
;   Each context should be of the form [<path> <args> <namespace> <context>*] where:
;    * path is the context path (string) as supplied to compojure.core/context
;    * args is a set of bindings as supplied to compojure.core/context
;    * namespace is the namespace to scan for handlers
;    * context is zero or more nested contexts
;   <env> :environment setting from main conf
;   <service> keyword identifying specific map in services
;   <services> map of services, used to determine how to connect to other services
;   <auths> should be a map of auth types to functions of request and permissions returning a ring response map
;
;   If :auth is specified in handler metadata it is used to lookup the auth function in <auths>, otherwise :default is used."
;  [env service services auths & contexts]
;  `(compojure/routes
;     (make-doc-handler ~service ~@contexts)
;     ~@(resources env service services auths contexts)))

(defn build-map-arg-resolver [& kvs]
  (let [arg-map (apply hash-map kvs)]
    (fn [param request]
      (get arg-map param))))

(defn build-fn-arg-resolver [& kvs]
  (let [arg-map (apply hash-map kvs)]
    (fn [param request]
      (when-let [fun (get arg-map param)]
        (fun request)))))

(defn request-arg-resolver [param request]
  (get request param))

(defn arg-resolver-middleware [handler & arg-resolvers]
  (fn [request]
    (handler (assoc request
               :rook (merge (or (:rook request) {})
                      {:default-arg-resolvers (concat (:default-arg-resolvers (:rook request))  arg-resolvers)})))))

(defn- get-compiled-paths-uncached [namespace]
  (->> (ns-paths namespace)
       (map (fn [[[request-method path] function-key]]
              (when-let [fun (ns-function namespace function-key)]
                [[request-method (clout/route-compile path)] fun])))
       (remove nil?)))

(def get-compiled-paths (memo/memo get-compiled-paths-uncached))

(defn clear-namespace-cache!
  "Clear namespace cache, forcing a re-scan of every namespace checked beforehand."
  []
  (memo/memo-clear! get-compiled-paths))

(defn namespace-middleware
  ([handler namespace]
  (fn [request]
    (let [rook-data (some (fn [[[request-method route] fun]]
                                 (when-let [route-params (and (or (= :all (:request-method request))
                                                                  (= (:request-method request) request-method))
                                                              (clout/route-matches route request))]
                                   {:route-params (merge (:route-params request) route-params)
                                    :rook (merge (or (:rook request) {})
                                            {:namespace namespace
                                             :function fun
                                             :metadata (meta fun)
                                             :arg-resolvers (:arg-resolvers (meta fun))})}))
                          (get-compiled-paths namespace))]
      (handler (merge request rook-data))))))

(defn rook-handler [request]
  (let [rook-data (-> request :rook)
        arg-resolvers (concat (-> rook-data :default-arg-resolvers)
                              (-> rook-data :arg-resolvers))
        fun  (-> rook-data :function)
        args (-> rook-data :metadata :arglists first)
        argument-values (map #(extract-argument-value % request arg-resolvers) args)]
    (when fun
      (apply fun argument-values))))

(defn namespace-handler
  ([namespace]
   (namespace-middleware rook-handler namespace))
  ([path namespace & handlers]
   (compojure/context path []
     (namespace-middleware
      (apply compojure/routes (concat handlers [rook-handler]))
      namespace))))