(ns io.aviso.rook.dispatcher
  "This namespace deals with dispatch tables mapping route specs of
  the form

      [method [path-segment ...]]

  to endpoint functions. The recognized format is described at length
  in the docstrings of the [[unnest-dispatch-table]] and
  [[request-route-spec]] functions exported by this namespace.

  The expected way to use this namespace is as follows:

   - namespaces correspond to resources;

   - [[namespace-dispatch-table]] produces a dispatch table for a single
     namespace

   - any number of such dispatch tables can be concatenated to form a
     dispatch table for a collection of resources

   - such compound dispatch tables can be compiled using
     [[compile-dispatch-table]].

  The individual _resource handler functions_ (functions defined by a namespace
  and either conforming to the naming convention, or defining :route-spec metadata)
  are expected to support a single
  arity only. The arglist for that arity and the metadata on the
  request handler function will be examined to determine the correct
  argument resolution strategy at dispatch table compilation time."
  {:added "0.1.10"}
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [clojure.set :as set]
            [io.aviso.rook :as rook]
            [io.aviso.rook.async :as rook-async]
            [io.aviso.rook.internals :as internals]
            [io.aviso.rook.schema-validation :as sv]))

;; TODO: Move functions exposed just for tests to an internal namespace

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
      (rook-async/ring-handler->async-handler handler)
      handler)))

(defn- variable? [x]
  (or (keyword? x) (symbol? x)))

(defn- compare-pathvecs
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

(defn- compare-route-specs
  "Uses compare-pathvecs first, breaking ties by comparing methods."
  [[method1 pathvec1] [method2 pathvec2]]
  (let [res (compare-pathvecs pathvec1 pathvec2)]
    (if (zero? res)
      (compare method1 method2)
      res)))

#_ (defn sort-dispatch-table
  [dispatch-table]
  (vec (sort compare-route-specs dispatch-table)))

(defn- sorted-routes
  "Converts the given map of route specs -> * to a sorted map."
  [routes]
  (into (sorted-map-by compare-route-specs) routes))

(defn- analyse-dispatch-table
  "Returns a map holding a map of route-spec* -> handler-sym at
  key :routes, a map of route-spec -> handler-map at key :handlers and
  a map of middleware-symbol -> middleware-spec at key :middleware.
  The structure of handler-maps is as required by handler-form;
  middleware-spec is the literal form specifying the middleware in the
  dispatch table; a route-spec* is a route-spec with keywords replaced
  by symbols in the pathvec."
  [dispatch-table]
  (loop [routes     {}
         handlers   {}
         middleware {}
         entries    (seq (unnest-dispatch-table dispatch-table))]
    (if-let [[method pathvec verb-fn-sym mw-spec] (first entries)]
      (let [handler-sym (gensym "handler_sym__")
            routes      (assoc routes
                          [method (keywords->symbols pathvec)] handler-sym)
            [middleware mw-sym]
            (if (contains? middleware mw-spec)
              [middleware (get middleware mw-spec)]
              (let [mw-sym (gensym "mw_sym__")]
                [(assoc middleware mw-spec mw-sym) mw-sym]))
            ns               (-> verb-fn-sym namespace symbol the-ns)
            ns-metadata      (meta ns)
            metadata         (merge (reduce-kv (fn [out k v]
                                                 (assoc out
                                                   k (if (symbol? v)
                                                       @(ns-resolve ns v)
                                                       v)))
                                      {}
                                      (dissoc ns-metadata :doc))
                               (meta (resolve verb-fn-sym)))
            sync?            (:sync metadata)
            route-params     (mapv (comp symbol name)
                               (filter keyword? pathvec))
            context          (:context (meta pathvec))
            arglist          (first (:arglists metadata))
            non-route-params (remove (set route-params) arglist)
            arg-resolvers    (:arg-resolvers metadata)
            schema           (:schema metadata)
            handler (cond->
                      {:middleware-sym   mw-sym
                       :route-params     route-params
                       :non-route-params non-route-params
                       :verb-fn-sym      verb-fn-sym
                       :arglist          arglist
                       :arg-resolvers    arg-resolvers
                       :schema           schema
                       :sync?            sync?
                       :metadata         metadata}
                      context
                      (assoc :context (string/join "/" (cons "" context))))
            handlers (assoc handlers handler-sym handler)]
        (recur routes handlers middleware (next entries)))
      {:routes     (sorted-routes routes)
       :handlers   handlers
       :middleware (set/map-invert middleware)})))

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

(defn- header-arg-resolver [sym]
  (fn [request]
    (-> request :headers (get (name sym)))))

(defn- param-arg-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request :params kw))))

(defn- request-key-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (kw request))))

(defn- route-param-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request :route-params kw))))

(defn- default-resolver [sym]
  (fn [request]
    (internals/extract-argument-value
      sym request (-> request :rook :arg-resolvers))))

(def standard-resolvers
  "A map of keyword -> (function of symbol returning a function of
  request)."
  {:request (constantly identity)
   :request-key request-key-resolver
   :header      header-arg-resolver
   :param       param-arg-resolver})

(def ^:private standard-resolver-keywords
  (set (keys standard-resolvers)))

(defn- arglist-resolver
  [arglist resolvers route-params]
  (let [route-params (set route-params)
        resolvers    (map (fn [arg]
                            (condp contains? arg
                              route-params (route-param-resolver arg)
                              resolvers    (get resolvers arg)
                              (default-resolver arg)))
                       arglist)]
    (if (seq resolvers)
      (apply juxt resolvers)
      (constantly ()))))

(defn- resolver-entry
  [arg resolver]
  (if (keyword? resolver)
    (if-let [f (get standard-resolvers resolver)]
      [arg (f arg)]
      (throw (ex-info (str "unknown resolver keyword: " resolver)
               {:arg arg :resolver resolver})))
    (if (ifn? resolver)
      [arg resolver]
      (throw (ex-info (str "non-keyword, non-ifn resolver: " resolver)
               {:arg arg :resolver resolver})))))

(defn- maybe-resolver-by-tag
  [arg]
  (let [meta-ks     (keys (meta arg))
        resolver-ks (filterv standard-resolver-keywords meta-ks)]
    (case (count resolver-ks)
      0 nil
      1 (resolver-entry arg (first resolver-ks))
      (throw (ex-info (str "ambiguously tagged formal parameter: " arg)
               {:arg arg :resolver-tags resolver-ks})))))

(defn- resolvers-for
  [arglist resolvers-meta]
  (into {}
    (keep (fn [arg]
            (cond
              (contains? resolvers-meta arg)
              (let [resolver (get resolvers-meta arg)]
                (resolver-entry arg resolver))
              (contains? (meta arg) :io.aviso.rook/resolver)
              (resolver-entry arg (:io.aviso.rook/resolver (meta arg)))
              :else
              (maybe-resolver-by-tag arg)))
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

(defn- applicable-middleware
  [specified-middleware arg-resolvers]
  (let [mw (eval specified-middleware)]
    (if (seq arg-resolvers)
      (let [resolvers (mapv eval arg-resolvers)]
        (fn [handler]
          (apply rook/wrap-with-arg-resolvers
            (mw handler) resolvers)))
      mw)))

(defn- build-dispatch-map
  "Returns a dispatch-map for use with map-traversal-dispatcher."
  [{:keys [routes handlers middleware]}
   {:keys [async?]}]
  (reduce (fn [dispatch-map [[method pathvec] handler-sym]]
            (let [apply-middleware (if async?
                                     apply-middleware-async
                                     apply-middleware-sync)

                  {:keys [middleware-sym route-params non-route-params
                          verb-fn-sym arglist arg-resolvers schema sync?
                          metadata context]}
                  (get handlers handler-sym)

                  resolve-args (arglist-resolver
                                 arglist
                                 (resolvers-for arglist (:resolvers metadata))
                                 route-params)

                  mw (applicable-middleware
                       (get middleware middleware-sym)
                       arg-resolvers)

                  f  (let [ef (eval verb-fn-sym)]
                       (fn wrapped-handler [request]
                         (apply ef (resolve-args request))))

                  h  (apply-middleware mw sync? f)
                  h  (fn wrapped-with-rook-metadata [request]
                       (h (-> request
                            (update-in [:rook :metadata]
                              merge (dissoc metadata :arg-resolvers))
                            ;; FIXME
                            (cond-> context
                              (update-in [:context] str context)))))]
              (add-dispatch-entries dispatch-map method pathvec h)))
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
      (map-traversal-dispatcher dispatch-map (doto (async/chan) (async/close!)))
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

  : Will be called with routes, handlers, middleware and should produce a Ring handler."
  ([dispatch-table]
     (compile-dispatch-table
       dispatch-table-compilation-defaults
       dispatch-table))
  ([options dispatch-table]
     (let [options       (merge dispatch-table-compilation-defaults options)
           build-handler (:build-handler-fn options)
           analysed-dispatch-table (analyse-dispatch-table dispatch-table)]
       (build-handler analysed-dispatch-table
         (select-keys options [:async?])))))

(defn- simple-namespace-dispatch-table
  "Examines the given namespace and produces a dispatch table in a
  format intelligible to compile-dispatch-table."
  ([ns-sym]
     (simple-namespace-dispatch-table [] ns-sym))
  ([context-pathvec ns-sym]
     (simple-namespace-dispatch-table context-pathvec ns-sym identity))
  ([context-pathvec ns-sym middleware]
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
        vec)]))

(defn namespace-dispatch-table
  "Examines the given namespaces and produces a dispatch table in a
  format intelligible to compile-dispatch-table.

  The individual namespaces are specified as vectors of the following
  shape:

      [context-pathvec? ns-sym middleware?]

  The optional fragments are interpreted as below (defaults listed in
  brackets):

   - context-pathvec? ([]):

     A context pathvec to be prepended to pathvecs for all entries
     emitted for this namespace.

   - middleware? (clojure.core/identity or as supplied in options?):

     Middleware to be applied to terminal handlers found in this
     namespace.

  The options map, if supplied, can include the following keys (listed
  below with their default values):

  :context-pathvec

  : _Default: []_

  : Top-level context-pathvec that will be prepended to
    context-pathvecs for the individual namespaces.

  :default-middleware

  : _Default: clojure.core/identity_

  : Default middleware used for namespaces for which no middleware
    was specified.

  Example call:

      (namespace-dispatch-table
        {:context-pathvec    [\"api\"]
         :default-middleware basic-middleware}
        ;; foo & bar use basic middleware:
        [[\"foo\"]  'example.foo]
        [[\"bar\"]  'example.bar]
        ;; quux has special requirements:
        [[\"quux\"] 'example.quux special-middleware])

  The resulting dispatch table in its unnested form will include
  entries such as

    [:get [\"api\" \"foo\"] 'example.foo/index identity]"
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

(defn namespace-handler
  "Produces a handler based on the given namespaces. Supports the same
  syntax namespace-dispatch-table does."
  [options? & ns-specs]
  (compile-dispatch-table (if (map? options?) options?)
    (apply namespace-dispatch-table options? ns-specs)))
