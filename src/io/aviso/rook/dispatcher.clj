(ns io.aviso.rook.dispatcher
  "This namespace deals with dispatch tables mapping route specs of
  the form

      [method [path-segment ...]]

  to resource handler functions. The recognized format is described at
  length in the docstrings of the [[unnest-dispatch-table]] and
  [[request-route-spec]] functions exported by this namespace.

  User code interested in setting up a handler for a RESTful API will
  typically not interact with this namespace directly; rather, it will
  use [[io.aviso.rook/namespace-handler]]. Functions exported by this
  namespace can be useful, however, to code that wishes to use the
  dispatcher in a more flexible way (perhaps avoiding the namespace to
  resource correspondence) and utility add-ons that wish to deal with
  dispatch tables directly.

  The expected way to use this namespace is as follows:

   - namespaces correspond to resources;

   - [[namespace-dispatch-table]] produces a dispatch table for a single
     namespace

   - any number of such dispatch tables can be concatenated to form a
     dispatch table for a collection of resources

   - such compound dispatch tables can be compiled using
     [[compile-dispatch-table]].

  The individual resource handler functions are expected to support a
  single arity only. The arglist for that arity and the metadata on
  the resource handler function will be examined to determine the
  correct argument resolution strategy at dispatch table compilation
  time."
  {:added "0.1.10"}
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [clojure.set :as set]
            [io.aviso.tracker :as t]
            [io.aviso.rook.internals :as internals]
            [clojure.string :as str]))

(def ^:private default-mappings

  "Default function -> route spec mappings.

  Namespace dispatch tables will by default include entries for public
  Vars named by the keys in this map, with methods and pathvecs
  provided by the values."

  {
   'new     [:get    ["new"]]
   'edit    [:get    [:id "edit"]]
   'show    [:get    [:id]]
   'update  [:put    [:id]]
   'patch   [:patch  [:id]]
   'destroy [:delete [:id]]
   'index   [:get    []]
   'create  [:post   []]
   }
  )

(defn- request-route-spec
  "Takes a Ring request map and returns `[method pathvec]`, where method
  is a request method keyword and pathvec is a vector of path
  segments.

  For example,

      GET /foo/bar HTTP/1.1

  become:

      [:get [\"foo\" \"bar\"]]

  The individual path segments are URL decoded; UTF-8 encoding is
  assumed."
  [request]
  [(:request-method request)
   (mapv #(java.net.URLDecoder/decode ^String % "UTF-8")
     (next (string/split (:uri request) #"/" 0)))])

(defn path-spec->route-spec
  "Takes a path-spec in the format `[:method \"/path/:param\"]` and
  returns the equivalent route-spec in the format `[:method
  [\"path\" :param]]`. If passed nil as input, returns nil."
  [path-spec]
  (if-not (nil? path-spec)
    (let [[method path] path-spec
          paramify (fn [seg]
                     (if (.startsWith ^String seg ":")
                       (keyword (subs seg 1))
                       seg))]
      [method (mapv paramify (next (string/split path #"/" 0)))])))

(defn unnest-dispatch-table
  "Given a nested dispatch table:

      [[method pathvec verb-fn middleware
        [method' pathvec' verb-fn' middleware' ...]
        ...]
       ...]

  produces a dispatch table with no nesting:

      [[method pathvec verb-fn middleware]
       [method' (into pathvec pathvec') verb-fn' middleware']
       ...]

  Entries may also take the alternative form of

      [pathvec middleware? & entries],

  in which case pathvec and middleware? (if present) will provide a
  context pathvec and default middleware for the nested entries
  without introducing a separate route."
  [dispatch-table]
  (letfn [(unnest-entry [default-middleware [x :as entry]]
            (cond
              (keyword? x)
              (let [[method pathvec verb-fn middleware & nested-table] entry]
                (if nested-table
                  (let [mw (or middleware default-middleware)]
                    (into [[method pathvec verb-fn mw]]
                      (unnest-table pathvec mw nested-table)))
                  (cond-> [entry]
                    (nil? middleware) (assoc-in [0 3] default-middleware))))

              (vector? x)
              (let [[context-pathvec & maybe-middleware+entries] entry
                    middleware (if-not (vector?
                                         (first maybe-middleware+entries))
                                 (first maybe-middleware+entries))
                    entries    (if middleware
                                 (next maybe-middleware+entries)
                                 maybe-middleware+entries)]
                (unnest-table context-pathvec middleware entries))))
          (unnest-table [context-pathvec default-middleware entries]
            (mapv (fn [[_ pathvec :as unnested-entry]]
                    (assoc unnested-entry 1
                           (with-meta (into context-pathvec pathvec)
                             {:context (into context-pathvec
                                         (:context (meta pathvec)))})))
              (mapcat (partial unnest-entry default-middleware) entries)))]
    (unnest-table [] nil dispatch-table)))

(defn- keywords->symbols
  "Converts keywords in xs to symbols, leaving other items unchanged."
  [xs]
  (mapv #(if (keyword? %)
           (symbol (name %))
           %)
    xs))

(defn- apply-middleware-sync
  "Applies middleware to handler in a synchronous fashion. Ignores
  sync?."
  [middleware sync? handler]
  (middleware handler))

(defn- apply-middleware-async
  "Applies middleware to handler in a synchronous or asynchronous
  fashion depending on whether sync? is truthy."
  [middleware sync? handler]
  (middleware
    (if sync?
      (internals/ring-handler->async-handler handler)
      handler)))

(defn- variable? [x]
  (or (keyword? x) (symbol? x)))

(defn compare-pathvecs
  "Uses lexicographic order. Variables come before literal strings (so
  that /foo/:id sorts before /foo/bar)."
  [pathvec1 pathvec2]
  (loop [pv1 (seq pathvec1)
         pv2 (seq pathvec2)]
    (cond
      (nil? pv1) (if (nil? pv2) 0 -1)
      (nil? pv2) 1
      :else
      (let [seg1 (first pv1)
            seg2 (first pv2)]
        (cond
          (variable? seg1) (if (variable? seg2)
                             (let [res (compare (name seg1) (name seg2))]
                               (if (zero? res)
                                 (recur (next pv1) (next pv2))
                                 res))
                             -1)
          (variable? seg2) 1
          :else (let [res (compare seg1 seg2)]
                  (if (zero? res)
                    (recur (next pv1) (next pv2))
                    res)))))))

(defn compare-route-specs
  "Uses compare-pathvecs first, breaking ties by comparing methods."
  [[method1 pathvec1] [method2 pathvec2]]
  (let [res (compare-pathvecs pathvec1 pathvec2)]
    (if (zero? res)
      (compare method1 method2)
      res)))

(defn sort-dispatch-table
  [dispatch-table]
  (vec (sort compare-route-specs dispatch-table)))

(defn- sorted-routes
  "Converts the given map of route specs -> * to a sorted map."
  [routes]
  (into (sorted-map-by compare-route-specs) routes))

(defn- analyze*
  [routes handlers middleware extra-arg-resolvers dispatch-table-entry]
  (if-let [[method pathvec verb-fn-sym mw-spec] dispatch-table-entry]
    (t/track
      (format "Analyzing resource handler function `%s'." verb-fn-sym)
      (let [handler-sym (gensym "handler_sym__")
            routes' (assoc routes
                      [method (keywords->symbols pathvec)] handler-sym)
            [middleware' mw-sym] (if (contains? middleware mw-spec)
                                   [middleware (get middleware mw-spec)]
                                   (let [mw-sym (gensym "mw_sym__")]
                                     [(assoc middleware mw-spec mw-sym) mw-sym]))
            ns (-> verb-fn-sym namespace symbol the-ns)
            ns-metadata (meta ns)
            metadata (merge (reduce-kv (fn [out k v]
                                         (assoc out
                                           k (if (symbol? v)
                                               @(ns-resolve ns v)
                                               v)))
                                       {}
                                       (dissoc ns-metadata :doc))
                            (meta (resolve verb-fn-sym)))
            sync? (:sync metadata)
            route-params (mapv (comp symbol name)
                               (filter keyword? pathvec))
            context (:context (meta pathvec))
            arglist (first (:arglists metadata))
            arg-resolvers (merge
                            extra-arg-resolvers
                            (binding [*ns* ns]
                              (eval (:arg-resolvers metadata))))
            handler (cond->
                      {:middleware-sym   mw-sym
                       :route-params     route-params
                       :verb-fn-sym      verb-fn-sym
                       :arglist          arglist
                       :arg-resolvers    arg-resolvers
                       :sync?            sync?
                       :metadata         metadata}
                      context
                      (assoc :context (string/join "/" (cons "" context))))
            handlers' (assoc handlers handler-sym handler)]
        [routes' handlers' middleware']))))

(defn- analyse-dispatch-table
  "Returns a map holding a map of route-spec* -> handler-sym at
  key :routes, a map of route-spec -> handler-map at key :handlers and
  a map of middleware-symbol -> middleware-spec at key :middleware.
  The structure of handler-maps is as required by handler-form;
  middleware-spec is the literal form specifying the middleware in the
  dispatch table; a route-spec* is a route-spec with keywords replaced
  by symbols in the pathvec.

  `options` should be a map of options or `nil`. Currently only one
  option is supported:

  :arg-resolvers

  : _Default: nil_

  : Can be used to specify default resolvers (which will be overridden
    by argument resolvers specified in namespace or function
    metadata)."
  [dispatch-table options]
  (let [extra-arg-resolvers (:arg-resolvers options)]
    (loop [routes     {}
           handlers   {}
           middleware {}
           entries    (seq (unnest-dispatch-table dispatch-table))]
      (if-let [[routes' handlers' middleware'] (analyze* routes handlers middleware extra-arg-resolvers (first entries))]
        (recur routes' handlers' middleware' (next entries))
        {:routes     (sorted-routes routes)
         :handlers   handlers
         :middleware (set/map-invert middleware)}))))

(defn- map-traversal-dispatcher
  "Returns a Ring handler using the given dispatch-map to guide
  dispatch. Used by build-map-traversal-handler. The optional
  not-found-response argument defaults to nil; pass in a closed
  channel for async operation."
  ([dispatch-map]
     (map-traversal-dispatcher dispatch-map nil))
  ([dispatch-map not-found-response]
     (fn rook-map-traversal-dispatcher [request]
       (loop [pathvec      (second (request-route-spec request))
              dispatch     dispatch-map
              route-params {}]
         (if-let [seg (first pathvec)]
           (if (contains? dispatch seg)
             (recur (next pathvec) (get dispatch seg) route-params)
             (if-let [v (::param-name dispatch)]
               (recur (next pathvec) (::param-next dispatch)
                 (assoc route-params v seg))
               ;; no match on path
               not-found-response))
           (if-let [h (or (get dispatch (:request-method request))
                          (get dispatch :all))]
             (h (assoc request :route-params route-params))
             ;; unsupported method for path
             not-found-response))))))

(defn make-header-arg-resolver [sym]
  (fn [request]
    (-> request :headers (get (name sym)))))

(defn make-param-arg-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request :params kw))))

(defn make-request-key-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (kw request))))

(defn make-route-param-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request :route-params kw))))

(defn make-resource-uri-arg-resolver [sym]
  (fn [request]
    (internals/resource-uri-for request)))

(defn make-injection-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request :io.aviso.rook/injections kw))))

(def standard-resolver-factories
  "A map of keyword -> (function of symbol returning a function of
  request)."
  {:request      (constantly identity)
   :request-key  make-request-key-resolver
   :header       make-header-arg-resolver
   :param        make-param-arg-resolver
   :injection    make-injection-resolver
   :resource-uri make-resource-uri-arg-resolver})

(def standard-arg-resolvers
  "A map of symbol -> (function of request)."
  {'request      identity
   'params       :params
   'params*      internals/clojurized-params-arg-resolver
   'resource-uri (make-resource-uri-arg-resolver 'resource-uri)})

(def ^:private standard-resolver-keywords
  (set (keys standard-resolver-factories)))

(defn make-default-resolver [sym]
  (or (get standard-arg-resolvers sym)
      ;; Soon we'll be getting rid of the whole concept of arg-resolvers in this particular
      ;; passion (which dates back to Rook 0.1.9 and earlier).
      (fn [request]
        (internals/extract-argument-value sym request (-> request :rook :arg-resolvers)))
      ;; We're still debating whether the right behavior is to throw an exception, or
      ;; just treat it the same as :request-key.
      #_ (throw (ex-info (format "No default argument resolver for symbol `%s'." sym)
               {:symbol sym
                :available-keywords standard-resolver-keywords
                :available-symbols (keys standard-arg-resolvers)}))))

(defn- arg-name [arg]
  (if (map? arg)
    (if-let [as (:as arg)]
      as
      (throw (ex-info "map argument has no :as key"
               {:arg arg})))
    arg))

(defn- arglist-resolver
  [arglist resolvers route-params]
  (let [route-params (set route-params)
        resolvers    (map (fn [arg]
                            (let [arg-symbol (arg-name arg)]
                              (t/track #(format "Creating argument resolver for `%s'." arg-symbol)
                                       (condp contains? arg-symbol
                                         route-params (make-route-param-resolver arg-symbol)
                                         resolvers (get resolvers arg-symbol)
                                         (make-default-resolver arg-symbol)))))
                       arglist)]
    (if (seq resolvers)
      (apply juxt resolvers)
      (constantly ()))))

(defn- resolver-entry
  [arg resolver]
  (if (keyword? resolver)
    (if-let [f (get standard-resolver-factories resolver)]
      [arg (f arg)]
      (throw (ex-info (format "Keyword %s does not identify a known argument resolver." resolver)
               {:arg arg :resolver resolver})))
    (if (ifn? resolver)
      [arg resolver]
      (throw (ex-info (format "Argument resolver value `%s' is neither a keyword not a function." resolver)
               {:arg arg :resolver resolver})))))

(defn- maybe-resolver-by-tag
  [arg]
  (let [meta-ks     (keys (meta arg))
        resolver-ks (filterv standard-resolver-keywords meta-ks)]
    (case (count resolver-ks)
      0 nil
      1 (resolver-entry arg (first resolver-ks))
      (throw (ex-info (format "Parameter `%s' has conflicting keywords identifying its argument resolution strategy: %s."
                              arg
                              (str/join ", " (resolver-ks)))
               {:arg arg :resolver-tags resolver-ks})))))

(defn- resolvers-for
  [arglist resolvers-map]
  (into {}
    (keep (fn [arg]
            (t/track #(format "Identifying argument resolver factory for `%s'." arg)
                     (let [arg (arg-name arg)]
                       (cond
                         (contains? resolvers-map arg)
                         (let [resolver (get resolvers-map arg)]
                           (resolver-entry arg resolver))
                         (contains? (meta arg) :io.aviso.rook/resolver)
                         (resolver-entry arg (:io.aviso.rook/resolver (meta arg)))
                         :else
                         (maybe-resolver-by-tag arg)))))
      arglist)))

(defn- add-dispatch-entries
  [dispatch-map method pathvec handler]
  (let [pathvec'      (mapv #(if (variable? %) ::param-next %) pathvec)
        dispatch-path (conj pathvec' method)
        binding-names (filter variable? pathvec)
        binding-paths (keep-indexed
                        (fn [i seg]
                          (if (variable? seg)
                            (-> (subvec pathvec' 0 i)
                              (into [])
                              (conj ::param-name))))
                        pathvec)]
    (reduce (fn [dispatch-map [name path]]
              (assoc-in dispatch-map path (keyword name)))
      (assoc-in dispatch-map dispatch-path handler)
      (map vector binding-names binding-paths))))

(defn- build-dispatch-map
  "Returns a dispatch-map for use with map-traversal-dispatcher."
  [{:keys [routes handlers middleware]}
   {:keys [async?]}]
  (reduce (fn [dispatch-map [[method pathvec] handler-sym]]
            (t/track #(format "Compiling handler for `%s'." (get-in handlers [handler-sym :verb-fn-sym]))
                     (let [apply-middleware (if async?
                                              apply-middleware-async
                                              apply-middleware-sync)

                           {:keys [middleware-sym route-params
                                   verb-fn-sym arglist arg-resolvers sync?
                                   metadata context]}
                           (get handlers handler-sym)

                           resolve-args (arglist-resolver
                                          arglist
                                          (resolvers-for arglist arg-resolvers)
                                          route-params)

                           mw (eval (get middleware middleware-sym))

                           f (let [ef (eval verb-fn-sym)]
                               (fn wrapped-handler [request]
                                 (apply ef (resolve-args request))))

                           h (apply-middleware mw sync? f)
                           h (fn wrapped-with-rook-metadata [request]
                               (h (-> request
                                      (update-in [:rook :metadata]
                                                 merge (dissoc metadata :arg-resolvers))
                                      ;; FIXME
                                      (cond-> context
                                              (update-in [:context] str context)))))]
                       (add-dispatch-entries dispatch-map method pathvec h))))
    {}
    routes))

(defn build-map-traversal-handler
  "Returns a form evaluating to a Ring handler that handles dispatch
  by using the pathvec and method of the incoming request to look up
  an endpoint function in a nested map.

  Can be passed to compile-dispatch-table using the :build-handler-fn
  option."
  [analysed-dispatch-table opts]
  (let [dispatch-map (build-dispatch-map analysed-dispatch-table opts)]
    (if (:async? opts)
      (map-traversal-dispatcher dispatch-map
        (doto (async/chan) (async/close!)))
      (map-traversal-dispatcher dispatch-map))))

(def ^:private dispatch-table-compilation-defaults
  {:async?           false
   :build-handler-fn build-map-traversal-handler})

(defn compile-dispatch-table
  "Compiles the dispatch table into a Ring handler.

  See the docstring of unnest-dispatch-table for a description of
  dispatch table format.

  Supported options and their default values:

  :async?

  : _Default: false_

  : Determines the way in which middleware is applied to the terminal
    handler. Pass in true when compiling async handlers.

  :build-handler-fn

  : _Default: [[build-map-traversal-handler]]_

  : Will be called with routes, handlers, middleware and should
    produce a Ring handler.

  :arg-resolvers

  : _Default: nil_

  : Can be used to specify default resolvers (which will be overridden
    by argument resolvers specified in namespace or function
    metadata)."
  ([dispatch-table]
     (compile-dispatch-table
       dispatch-table-compilation-defaults
       dispatch-table))
  ([options dispatch-table]
     (let [options       (merge dispatch-table-compilation-defaults options)
           build-handler (:build-handler-fn options)
           analysed-dispatch-table (analyse-dispatch-table
                                     dispatch-table options)]
       (build-handler analysed-dispatch-table options))))

(defn- simple-namespace-dispatch-table
  "Examines the given namespace and produces a dispatch table in a
  format intelligible to compile-dispatch-table."
  ([ns-sym]
     (simple-namespace-dispatch-table [] ns-sym))
  ([context-pathvec ns-sym]
     (simple-namespace-dispatch-table context-pathvec ns-sym identity))
  ([context-pathvec ns-sym middleware]
   (t/track
     #(format "Identifying resource handler functions in `%s.'" ns-sym)
     (try
       (if-not (find-ns ns-sym)
         (require ns-sym))
       (catch Exception e
         (throw (ex-info "failed to require ns in namespace-dispatch-table"
                         {:context-pathvec context-pathvec
                          :ns              ns-sym
                          :middleware      middleware}
                         e))))
     [(->> ns-sym
           ns-publics
           (keep (fn [[k v]]
                   (if (ifn? @v)
                     (if-let [route-spec (or (:route-spec (meta v))
                                             (path-spec->route-spec
                                               (:path-spec (meta v)))
                                             (get default-mappings k))]
                       (conj route-spec (symbol (name ns-sym) (name k)))))))
           (list* context-pathvec middleware)
           vec)])))

(defn namespace-dispatch-table
  "Similar to [[io.aviso.rook/namespace-handler]], but stops short of
  producing a handler, returning a dispatch table instead. See the
  docstring of [[io.aviso.rook/namespace-handler]] for a description
  of ns-spec syntax and a list of supported options (NB. `async?` is
  irrelevant to the shape of the dispatch table).

  The resulting dispatch table in its unnested form will include
  entries such as

      [:get [\"api\" \"foo\"] 'example.foo/index identity]."
  [options? & ns-specs]
  (let [default-opts {:context-pathvec    []
                      :default-middleware identity}
        opts         (if (map? options?)
                       (merge default-opts options?))
        ns-specs     (if opts
                       ns-specs
                       (cons options? ns-specs))
        {outer-context-pathvec :context-pathvec
         default-middleware    :default-middleware}
        (or opts default-opts)]
    [(reduce into [outer-context-pathvec default-middleware]
       (map (fn [[context-pathvec? ns-sym middleware?]]
              (let [context-pathvec (if (vector? context-pathvec?)
                                      context-pathvec?)
                    middleware      (if context-pathvec
                                      middleware?
                                      ns-sym)
                    ns-sym          (if context-pathvec
                                      ns-sym
                                      context-pathvec?)]
                (assert (symbol? ns-sym)
                  "Malformed ns-spec passed to namespace-dispatch-table")
                (simple-namespace-dispatch-table
                  (or context-pathvec [])
                  ns-sym
                  (or middleware default-middleware))))
         ns-specs))]))
