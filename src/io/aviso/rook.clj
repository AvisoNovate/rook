(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require
    [clojure.tools.logging :as l]
    [compojure.core :as compojure]
    [clout.core :as clout]))

(def
  DEFAULT-MAPPINGS
  "Default mappings for route specs to functions. We use keyword for function name for increased readability.
  If a public method whose name matches a default mapping exists, then it will be added using the
  default mapping; for example, a method named \"index\" will automatically be matched against \"GET /\".
  This can be overriden by providing meta-data on the functions."
  [[[:get "/new"] :new] ; :new is used to present an HTML form to a user, to create the entity, not needed in a pure web service
   [[:get "/:id"] :show]
   [[:put "/:id"] :update]
   [[:patch "/:id"] :update]
   [[:get "/:id/edit"] :edit] ; :edit parallels :new, a user-centric HTML form, not needed in a pure web service
   [[:delete "/:id"] :destroy]
   [[:get "/"] :index]
   [[:post "/"] :create]])

(defn- symbol-for-function?
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
      (get (:route-params request) arg-kw)
      (get (:params request) api-kw)
      (get (:params request) arg-kw))))

(defn- ns-function
  "Return the var for the given namespance and function keyword."
  [namespace function-key]
  (when-let [f (ns-resolve namespace (symbol (name function-key)))]
    (when (symbol-for-function? f) ;it has to be a function all right
      f)))

(defn- success?
  "Passed a resonse map, determines if the status indicates success."
  [{:keys [status]}]
  (<= 200 status 399))

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
     function-key is a keyword derived from the simple name of the function with the namespace"
  [namespace]
  (when-not (find-ns namespace)
    (require namespace))
  (concat
    (remove nil?
            (map function-entry
                 (filter symbol-for-function? (map second (ns-publics namespace)))))
    DEFAULT-MAPPINGS))

(defn get-function-meta
  "Get meta for route-mapping and namespace."
  [namespace [path-spec function-key]]
  (when-let [f (ns-function namespace function-key)]
    (let [[method path] path-spec]
      (assoc (meta f) :method method :path path))))

(defn- scan-namespace-for-doc
  "Build a map of functions in namespace <-> url prefixes."
  [namespace]
  (->> namespace
       ns-paths
       (map #(get-function-meta namespace %))
       (remove nil?)))

(defn build-map-arg-resolver
  "Build an argument resolver which takes a list of keys and constant values and when required argument
has a corresponding key in the map built from keys and constant values - the value for such key is returned."
  [& kvs]
  (let [arg-map (apply hash-map kvs)]
    (fn [arg request]
      (get arg-map arg))))

(defn build-fn-arg-resolver
  "Build an argument resolver which takes a list of keys and functions and when required argument has
a corresponding key in the built from keys and functions mentioned before - the function is invoked with request as argument. "
  [& kvs]
  (let [arg-map (apply hash-map kvs)]
    (fn [arg request]
      (when-let [fun (get arg-map arg)]
        (fun request)))))

(defn request-arg-resolver
  "Standard argument resolver that adds values from request to map to resolved arguments."
  [arg request]
  (get request arg))

(defn arg-resolver-middleware
  "Middleware which adds provided argument resolvers to [:rook :default-arg-resolvers] collection."
  [handler & arg-resolvers]
  (fn [request]
    (handler (assoc request
               :rook (merge (:rook request)
                            {:default-arg-resolvers (concat (:default-arg-resolvers (:rook request)) arg-resolvers)})))))

(defn get-available-paths
  "Scan namespace for available routes - only those that have available function are returned.

  Routes are sorted by the line number from metadata - which can be troubling if you have the same namespace in many files.

  But then, unless your name is Rich, you're in trouble already... "
  [namespace]
  (->> (ns-paths namespace)
       (map (fn [[[request-method path] function-key]]
              (when-let [fun (ns-function namespace function-key)]
                [[request-method path] fun])))
       (remove nil?)
       (sort-by #(or (-> % second meta :line) 0)))) ;sadly, the namespace stores interned values

(defn- get-compiled-paths
  [namespace]
  (l/debugf "Scanning %s for mappable functions" namespace)
  (->> (get-available-paths namespace)
       (map (fn [[[request-method path] fun]]
              ;; It would be nice if there was a way to qualify the path for when we are nested
              ;; inside a Compojure context, but we'd need the request to do that.
              (l/debugf "Mapping %s `%s' to %s" (-> request-method name .toUpperCase) path fun)
              [[request-method (clout/route-compile path)] fun]))
       (remove nil?)
       doall))

(defn- identify-rook-data
  "Uses the compiled paths to identify the matching function to be invoked and returns
  the rook data to be added to the request. Returns nil on no match, or a map
  containing :rook and :route-params if there is a match."
  [request namespace compiled-paths]
  (some (fn [[[request-method route] fun]]
          (when-let [route-params (and (or (= :all (:request-method request))
                                           (= (:request-method request) request-method))
                                       (clout/route-matches route request))]
            {:route-params (merge (:route-params request) route-params)
             :rook         (merge (:rook request)
                                  {:namespace     namespace
                                   :function      fun
                                   :metadata      (meta fun)
                                   :arg-resolvers (:arg-resolvers (meta fun))})}))
        compiled-paths))

(defn namespace-middleware
  "Middleware that scans provided namespace and if any of the functions defined there matches the route spec -
  either by metadata or by default mappings from function name - sets this functions metadata in request map.

  This does not invoke the function; that is the responsibility of the rook-handler function. Several additional
  middleware filters will typically sit between identifying the function and actually invoking it."
  [handler namespace]
  (let [compiled-paths (get-compiled-paths namespace)]
    (fn [request]
      (if-let [rook-data (identify-rook-data request namespace compiled-paths)]
        (handler (merge request rook-data))
        (handler request)))))

(defn rook-handler
  "Handler that uses information from :rook entry in the request map to invoke the previously
  identified function, after resolving the function's arguments."
  [request]
  (let [rook-data (-> request :rook)
        arg-resolvers (concat (-> rook-data :default-arg-resolvers)
                              (-> rook-data :arg-resolvers))
        fun (-> rook-data :function)
        args (-> rook-data :metadata :arglists first)
        argument-values (map #(extract-argument-value % request arg-resolvers) args)]
    (when fun
      (l/debug "Invoking handler function" fun)
      (apply fun argument-values))))

(defn namespace-handler
  "Helper handler, which wraps rook-handler in namespace middleware.

  The advanced version also takes a path for compojure.core/context and an optional list of sub-handlers that
  will be invoked BEFORE rook-handler."
  ;; I'm thinking that handlers is wrong; the handlers should actually be middleware.
  ([namespace]
   (namespace-middleware rook-handler namespace))
  ([path namespace & handlers]
   (compojure/context path []
                      (namespace-middleware
                        (if (empty? handlers)
                          rook-handler
                          (apply compojure/routes (concat handlers [rook-handler])))
                        namespace))))