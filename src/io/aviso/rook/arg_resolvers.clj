(ns io.aviso.rook.arg-resolvers
  "Built-in implementations and support functions for argument resolver generators."
  {:added "0.2.2"})

(defn non-nil-arg-resolver
  "Returns an arg resolver that extracts a value nested in the context.

  The value must be non-nil, or an exception is thrown.

  sym
  : The symbol for which the argument resolver was created; identified in the exception.

  ks
  : a sequence of keys."
  [sym ks]
  (fn [context]
    (let [v (get-in context ks)]
      (if (some? v)
        v
        (throw (ex-info "Resolved argument value was nil."
                        {:type ::nil-argument-value
                         :parameter sym
                         :context-key-path ks}))))))

(defn standard-arg-resolver
  "Returns an arg resolver that extracts a value nested in the context.

  ks
  : A sequence of keys."
  [ks]
  (fn [context]
    (get-in context ks)))


(defn meta-value-for-parameter
  "Given a parameter symbol and a metadata key, returns the value for that metadata key.

  It is presumed that the value is a keyword, though no check is made.

  When the metadata value is explicitly true, the symbol is converted into a simple (not namespaced)
  keyword."
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
  (non-nil-arg-resolver sym [:request (meta-value-for-parameter sym :request)]))

(defn ^:private path-param-resolver
  [sym]
  (non-nil-arg-resolver sym [:request :path-params (meta-value-for-parameter sym :path-param)]))

(defn ^:private query-param-resolver
  [sym]
  (standard-arg-resolver
    [:request :query-params (meta-value-for-parameter sym :query-param)]))

(defn ^:private form-param-resolver
  [sym]
  (standard-arg-resolver [:request :form-params (meta-value-for-parameter sym :form-param)]))

(def default-arg-resolvers
  "Defines the following argument resolvers:

  :request
  : The argument resolves to a key in the :request map.
  : An exception is thrown if the resolved value is nil.

  :path-param
  : The argument resolves to a path parameter.
  : An exception is thrown if the resolved value is nil.

  :query-param
  : The argument resolves to a query parameter, as applied by the
    default io.pedestal.http.route/query-params interceptor.

  :form-param
  : As :query-param, but for the encoded form parameters.
    Assumes the necessary middleware to process the body into form parameters
    and keywordize the keys is present.

  For these resolvers if the meta data value is exactly the value true
  (e.g., just using ^:request),
  then the effective value is a keyword generated from the parameter symbol."
  {:request request-resolver
   :path-param path-param-resolver
   :query-param query-param-resolver
   :form-param form-param-resolver})

(defn inject-resolver
  "For a given meta-data key and map of injections, returns
  an arg-resolver generator, wrapped in a map that can be
  merged with [[default-arg-resolvers]].

  Parameters are converted to keys into the injections map.
  The injections map should use keyword keys.

  Will throw an exception if a parameter symbol fails to match a non-nil
  value in the injections map."
  [meta-key injections]
  (let [generator (fn [sym]
                    (if-let [value (->> (meta-value-for-parameter sym meta-key)
                                        (get injections))]
                      (fn [_] value)
                      (throw (ex-info "Unknown injection key."
                                      {:type ::unknown-injection
                                       :symbol sym
                                       :meta-key meta-key
                                       :injection-keys (keys injections)}))))]
    {meta-key generator}))
