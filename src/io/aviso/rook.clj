(ns io.aviso.rook
  "Rook is used to map the functions of a namespace as web request endpoints, based largely
  on metadata on the functions."
  (:require [io.aviso.rook.internals :refer [deep-merge to-message into+]]
            [io.aviso.rook.arg-resolvers :refer [default-arg-resolvers]]
            [clojure.core.async :refer [go <!]]
            [clojure.core.async.impl.protocols :refer [ReadPort]]
            [io.pedestal.interceptor :refer [interceptor]]))

;; Remember that a parameter is the symbol defined by a function, and an argument
;; is the actual value passed to the function.

(def default-options
  "Default options when generating the routing table.

   Defines a base set of argument resolvers ([[default-arg-resolvers]]),
   an empty map of constraints, an empty list of interceptors, and
   an empty map of interceptor definitions."
  {:arg-resolvers default-arg-resolvers
   :constraints {}
   :interceptors []
   :interceptor-defs {}})

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

(defn ^:private read-port?
  [v]
  (satisfies? ReadPort v))

(defn ^:private wrap-to-assoc-response
  "Invokes the function yielding the response.  Returns either the context with the :response
  key set, or a channel that will convey the context when ready (if the endpoint function returns
  a channel)."
  [f]
  (fn [context]
    (let [v (f context)]
      (if (read-port? v)
        (go (assoc context :response (<! v)))
        (assoc context :response v)))))

(defn ^:private fn-as-interceptor
  "Converts a function with a list of argument resolvers into a Pedestal interceptor."
  [endpoint resolvers]
  (let [f (-> endpoint :var deref)
        supplier (when (seq resolvers)
                   (let [applier (apply juxt resolvers)]
                     (fn [context]
                       (try
                         (applier context)
                         (catch Throwable t
                           (throw (ex-info "Exception resolving endpoint function arguments."
                                           endpoint
                                           t)))))))
        endpoint-kw (keyword (-> endpoint :meta :ns ns-name name)
                             (-> endpoint :meta :name name))
        generate-response (if supplier
                            (fn [context]
                              (apply f (supplier context)))
                            ;; Keep things simple in the occasional case that there are no arguments.
                            (fn [_]
                              (f)))]
    (interceptor {:name endpoint-kw
                  :enter (wrap-to-assoc-response generate-response)})))

(defn ^:private parameter->resolver
  [parameter arg-resolvers]
  (let [parameter-meta (meta parameter)
        ;; We're allergic to ambiguity, so we build a map of all the arg-resolvers, triggered
        ;; by the metadata. We hope to get exactly one match.
        fn-resolvers (reduce-kv (fn [m k v]
                                  (cond-> m
                                    (get parameter-meta k)
                                    (assoc  k
                                           (try
                                             (v parameter)
                                             (catch Throwable t
                                               (throw (ex-info (format "Exception invoking argument resolver generator %s: %s"
                                                                       k
                                                                       (to-message t))
                                                               {:parameter parameter
                                                                :parameter-meta parameter-meta
                                                                :arg-resolver k}
                                                               t)))))))
                                {}
                                arg-resolvers)]
    (case (count fn-resolvers)

      1
      (-> fn-resolvers vals first)

      0
      (throw (ex-info (format "No argument resolver found for parameter %s." parameter)
                      {:parameter parameter
                       :parameter-meta parameter-meta
                       :arg-resolvers (keys fn-resolvers)}))

      ;; Otherwise
      (throw (ex-info (format "Multiple argument resolvers apply to parameter %s." parameter)
                      {:parameter parameter
                       :parameter-meta parameter-meta
                       :arg-resolvers (keys fn-resolvers)})))))

(defn ^:private resolve-interceptor
  [interceptor-defs endpoint interceptor]
  (cond
    (keyword? interceptor)
    (let [interceptor-def (get interceptor-defs interceptor)]
      (cond

        (nil? interceptor-def)
        (throw (ex-info "Unknown interceptor definition."
                        {:interceptor interceptor
                         :intereceptor-defs (keys interceptor-defs)}))

        ;; This is our special case, a function indicates an interceptor
        ;; generator, which is passed the endpoint and returns a real interceptor.
        ;; Normally, an interceptor that's a function is interpretted as
        ;; a Ring handler that can only occur at the end of an interceptor pipeline.
        (fn? interceptor-def)
        (interceptor-def endpoint)

        ;; Otherwise, a preconfigured interceptor, or any of the other things
        ;; that are acceptible in a Pedestal routing table.

        :else
        interceptor-def))

    :else
    interceptor))

(defn ^:private build-pedestal-route
  [endpoint options]
  (try
    (when-not (-> endpoint :meta :rook-route vector?)
      (throw (IllegalArgumentException. ":root-route metadata must be a vector")))

    (let [{:keys [arg-resolvers interceptors interceptor-defs constraints prefix]} options
          {:keys [arglists route-name]
           fn-arg-resolvers :arg-resolvers
           fn-interceptors :interceptors
           [verb path fn-constraints] :rook-route} (:meta endpoint)
          arg-resolvers' (do
                           (when-not (= 1 (count arglists))
                             (throw (IllegalArgumentException. "Endpoint function must have exactly one arity.")))

                           (when-not (and (keyword? verb)
                                          (string? path))
                             (throw (IllegalArgumentException. "Route for endpoint function is not valid.")))

                           (merge arg-resolvers fn-arg-resolvers))
          interceptors' (mapv #(resolve-interceptor interceptor-defs endpoint %)
                              (into interceptors fn-interceptors))
          path' (str prefix path)
          constraints' (merge constraints fn-constraints)
          resolvers (mapv #(parameter->resolver % arg-resolvers') (first arglists))
          fn-interceptor (fn-as-interceptor endpoint resolvers)]
      (cond->
        [path' verb (conj interceptors' fn-interceptor)]
        route-name (conj :route-name route-name)
        (seq constraints') (conj :constraints constraints')))
    (catch Throwable t
      (throw (ex-info (format "Exception building route for %s." (:endpoint-name endpoint))
                      endpoint
                      t)))))

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

(def ^:private inherited-options [:arg-resolvers :interceptors :constraints])

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
                         nested-options (-> options
                                            (deep-merge (select-keys ns-definition' inherited-options)
                                                        (-> current-ns meta (select-keys inherited-options)))
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
  : Map from keyword to argument resolver generator.
    This map, if present, is merged into the containing
    namespaces's map of argument resolvers.
  : An argument resolver generator is passed a symbol (the parameter) and returns a resolver function.
    The resolver function is invoked every time the endpoint function is invoked: it is passed
    the Pedestal context, and returns the value for the parameter.

  :constraints
  : Map from keyword to regular expression.
    This map will be inherted and extended by nested namespaces.

  :interceptors
  : Vector of Pedestal interceptors for the namespace.
    These interceptors will apply to all routes.
    Individual routes may define additional interceptors.

  Each namespace may define metadata for :arg-resolvers, :constraints, and :interceptors.
  The supplied values are merged, or concatenated, to define defaults for any mapped functions
  in the namespace, and for any nested namespaces.

  Alternately, a namespace definition may just be a symbol, used to identify the namespace.

  Endpoint functions within a namespace have a :rook-route metadata value.  This is a two or
  three element vector, consisting of a method
  (:get, :post, etc.), a path string, and optionally, a map of constraints.

  Endpoint functions should have a single arity.

  Each parameter of the endpoint function must have metadata identifying how the argument value
  is to be generated; these are defined by the arg-resolvers in effect for the function.

  The options map provides overrides of [[default-options]].
  Supplied options are deep merged into the defaults.

  The :interceptor-defs option provides extra levels of indirection between an endpoint
  and the interceptors it requires.
  It allows for interceptors to be specified as a keyword.
  The corresponding value in the interceptor-defs map may either be a previously
  instantiated interceptor, or can be an interceptor generator function.

  In the latter case, a map definining the endpoint is passed to the generator function,
  which returns an interceptor, customized for the specific endpoint."
  [namespace-map options]
  (gen-routes namespace-map (deep-merge default-options options)))
