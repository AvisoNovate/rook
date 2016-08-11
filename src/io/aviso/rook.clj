(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require [io.aviso.rook.internals :refer [deep-merge to-message into+]]
            [io.pedestal.interceptor :refer [interceptor]]))


(defn ^:private standard-arg-resolver
  [sym ks]
  (fn [context]
    (let [v (get-in context ks)]
      (if (some? v)
        v
        (throw (ex-info "Resolved argument value was nil."
                        {:parameter sym
                         :context-key-path ks}))))))

(defn ^:private from-meta
  [sym meta-key]
  (let [meta-value (-> sym meta (get meta-key))]
    ;; This catches the typical default cause where the meta value is true,
    ;; typically meaning the ^:my-resolver format was used. In that situation,
    ;; convert the symbol name to an unqualified keyword.
    (if (true? meta-value)
      (-> sym name keyword)

      meta-value)))

(defn ^:private request-resolver
  [sym]
  (standard-arg-resolver sym [:request (from-meta sym :request)]))

(defn ^:private path-param-resolver
  [sym]
  (standard-arg-resolver sym [:request :path-params (from-meta sym :path-param)]))

(def default-arg-resolvers
  {:request request-resolver
   :path-param path-param-resolver})

(def default-options
  {:arg-resolvers default-arg-resolvers
   :constraints {}
   :interceptors []})

(defn ^:private find-namespace
  [sym]
  (require sym)
  (the-ns sym))

(defn ^:private endpoint-function
  "If a var has :rook-route metadata, then wrap it up in a map used elsewhere."
  [var]
  (when-let [metadata (meta var)]
    (and (:rook-route metadata)
         {:var var
          :meta metadata
          :endpoint-name (str (-> metadata :ns ns-name name)
                              "/"
                              (-> metadata :name name))})))

(defn ^:private fn-as-interceptor
  "Converts a function with a list of argument resolvers into a Pedestal interceptor."
  [endpoint arg-resolvers]
  (let [f (-> endpoint :var deref)
        supplier (when (seq arg-resolvers)
                   (let [applier (apply juxt arg-resolvers)]
                     (fn [context]
                       (try
                         (applier context)
                         (catch Throwable t
                           (throw (ex-info "Exception resolving endpoint function arguments."
                                           endpoint
                                           t)))))))
        endpoint-kw (keyword (-> endpoint :meta :ns ns-name name)
                             (-> endpoint :meta :name name))
        ;; TODO: Handle the case where the returned value is a core.async channel.
        enter-fn (if supplier
                   (fn [context]
                     (assoc context :response (apply f (supplier context))))
                   (fn [context]
                     (assoc context :response (f))))]
    (interceptor {:name endpoint-kw
                  :enter enter-fn})))

(defn ^:private arg->resolver
  [parameter arg-resolvers endpoint]
  (let [parameter-meta (meta parameter)
        ;; We're allergic to ambiguity, so we build a map of all the arg-resolvers, triggered
        ;; by the metadata. We hope to get exactly one match.
        fn-resolvers (reduce-kv (fn [m k v]
                                  (if (get parameter-meta k)
                                     (assoc m k
                                            (try
                                              (v parameter)
                                              (catch Throwable t
                                                (throw (ex-info (format "Exception invoking argument resolver generator %s: %s"
                                                                        k
                                                                        (to-message t))
                                                                {:endpoint endpoint
                                                                 :parameter parameter
                                                                 :parameter-meta parameter-meta
                                                                 :arg-resolver k}
                                                                t)))))
                                     m))
                                {}
                                arg-resolvers)]
    (case (count fn-resolvers)

      0
      (throw (ex-info (format "No argument resolver found for parameter %s." parameter)
                      {:endpoint endpoint
                       :parameter parameter
                       :parameter-meta parameter-meta
                       :arg-resolvers (keys fn-resolvers)}))

      1
      (-> fn-resolvers vals first)

      ;; Otherwise
      (throw (ex-info (format "Multiple argument resolvers apply to parameter %s." parameter)
                      {:endpoint endpoint
                       :parameter parameter
                       :parameter-meta parameter-meta
                       :arg-resolvers (keys fn-resolvers)})))))

(defn ^:private build-pedestal-route
  [endpoint options]
  (let [{:keys [arg-resolvers interceptors constraints prefix]} options
        {:keys [arglists]
         fn-arg-resolvers :arg-resolvers
         fn-interceptors :interceptors
         [verb path fn-constraints] :rook-route} (:meta endpoint)
        _ (do
            (when-not (= 1 (count arglists))
              (throw (ex-info "Endpoint function must have exactly one arity."
                              endpoint)))

            (when-not (and (keyword? verb)
                           (string? path))
              (throw (ex-info "Route for endpoint function is not valid."
                              endpoint))))
        arg-resolvers' (merge arg-resolvers fn-arg-resolvers)
        interceptors' (into interceptors fn-interceptors)
        path' (str prefix path)
        constraints' (merge constraints fn-constraints)
        fn-arg-resolvers (mapv #(arg->resolver % arg-resolvers' endpoint) (first arglists))
        fn-interceptor (fn-as-interceptor endpoint fn-arg-resolvers)]
    ;; TODO: Add an optional :route-name
    (cond->
      [path' verb (conj interceptors' fn-interceptor)]
      (seq constraints') (conj :constraints constraints'))))

(defn ^:private routes-in-namespace
  [namespace options]
  (->> namespace
       ns-publics
       vals
       (keep endpoint-function)
       ;; Sorting shouldn't be necessary, but helps make some tests more predictable;
       ;; otherwise, subject to hash map ordering, which is not predictable.
       (sort-by #(-> % :endpoint :endpoint-name))
       (mapv #(build-pedestal-route % options))))

(defn ^:private gen-routes
  [namespace-map options]
  (reduce-kv (fn [routes path ns-definition]
               (let [ns-definition' (if (symbol? ns-definition)
                                      {:ns ns-definition}
                                      ns-definition)
                     {ns-symbol :ns
                      nested-ns-map :nested} ns-definition']
                 (try
                   (let [current-ns (find-namespace ns-symbol)
                         current-ns-meta (-> current-ns meta (select-keys [:arg-resolvers :interceptors :constraints]))
                         nested-options (-> options
                                            (deep-merge current-ns-meta)
                                            (update :prefix str path))]
                     (into+ routes
                            (routes-in-namespace current-ns nested-options)
                            (when nested-ns-map
                              (gen-routes nested-ns-map nested-options))))
                   (catch Throwable t
                     (throw (ex-info (format "Exception mapping routes for %s."
                                             (name ns-symbol))
                                     ns-definition'
                                     t))))))
             []
             namespace-map))

(defn gen-table-routes
  "Generates a vector of Pedestal table routes for some number of namespaces.

  The namespace-map is a map from URL prefix (e.g., \"/users\") to a Rook namespace definition.

  The Rook namespace definition is a map with keys that define how Rook will map functions in the
  namespace as routes and handlers in the returned table.

  :ns
  : A symbol identifying the namespace.  Rook will require the namespace and scan it for functions to
   create routes for.

  :nested
  : A namespace map of nested namespaces; these inherit the prefix and other attributes of the containing
   namespace.

  :arg-resolvers
  : Map from keyword to argument resolver generator.  This map, if present, is merged into the containing
   namespaces's map of argument resolvers.

  :constraints
  : Map from keyword to regular expression. This map will be inherted and extended by nested namespaces.

  :interceptors
  : Vector of Pedestal interceptors for the namespace. These interceptors will apply to all routes.
   Individual routes may define additional interceptors.

  Each namespace may define metadata for :arg-resolvers, :constraints, and :interceptors.
  The supplied values are merged, or concatenated, to define defaults for any mapped functions
  in the namespace, and for any nested namespaces.

  Alternately, a namespace definition may just be a symbol, used to identify the namespace.

  Mapped functions will have a :rook-route metadata value.

  "
  [namespace-map options]
  (gen-routes namespace-map (deep-merge default-options options)))
