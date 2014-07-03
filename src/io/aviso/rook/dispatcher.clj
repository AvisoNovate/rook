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
            ;; Seems like we should just do the *ns* binding trick once
            ;; per namespace. Possibly as an additional map passed into
            ;; analyze*
            ns-metadata (binding [*ns* ns]
                          (-> ns meta eval (dissoc :doc)))
            metadata (merge ns-metadata (meta (resolve verb-fn-sym)))
            sync? (:sync metadata)
            route-params (mapv (comp symbol name)
                               (filter keyword? pathvec))
            context (:context (meta pathvec))
            arglist (first (:arglists metadata))
            ;; :arg-resolvers is an option passed to compile-dispatch-table,
            ;; and metadata is merged onto that.
            arg-resolvers (merge extra-arg-resolvers (:arg-resolvers metadata))
            handler (cond->
                      {:middleware-sym mw-sym
                       :route-params   route-params
                       :verb-fn-sym    verb-fn-sym
                       :arglist        arglist
                       :arg-resolvers  arg-resolvers
                       :sync?          sync?
                       :metadata       metadata}
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
  : Map of symbol to argument resolver (keyword or function) that serves
    as a default that can be extended with function or namespace :arg-resolvers
    metadata."
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
      (internals/get-injection request kw))))

(def default-resolver-factories
  "A map of keyword -> (function of symbol returning a function of
  request)."
  {:request      (constantly identity)
   :request-key  make-request-key-resolver
   :header       make-header-arg-resolver
   :param        make-param-arg-resolver
   :injection    make-injection-resolver
   :resource-uri make-resource-uri-arg-resolver})

(def default-arg-symbol->resolver
  "A map of symbol -> (function of request)."
  {'request      identity
   'params       :params
   'params*      internals/clojurized-params-arg-resolver
   'resource-uri (make-resource-uri-arg-resolver 'resource-uri)})

(defn- symbol-for-argument [arg]
  "Returns the argument symbol for an argument; this is either the argment itself or
  (if a map, for destructuring) the :as key of the map."
  (if (map? arg)
    (if-let [as (:as arg)]
      as
      (throw (ex-info "map argument has no :as key"
               {:arg arg})))
    arg))

(defn- resolver-from-metadata
  [resolver-factories arg value]
  (cond
    (fn? value)
    value

    (keyword? value)
    (if-let [f (get resolver-factories value)]
      (f arg)
      (throw (ex-info (format "Keyword %s does not identify a known argument resolver." value)
                      {:arg arg :resolver value :resolver-factories resolver-factories})))

    :else
    (throw (ex-info (format "Argument resolver value `%s' is neither a keyword not a function." value)
                    {:arg arg :resolver value}))))

(defn- find-argument-resolver-tag
  [resolver-factories arg arg-meta]
  (let [resolver-ks (filterv #(contains? resolver-factories %) (keys arg-meta))]
    (case (count resolver-ks)
      0 nil
      1 (first resolver-ks)
      (throw (ex-info (format "Parameter `%s' has conflicting keywords identifying its argument resolution strategy: %s."
                              arg
                              (str/join ", " (resolver-ks)))
                      {:arg arg :resolver-tags resolver-ks})))))

(defn- identify-argument-resolver
  "Identifies the specific argument resolver function for an argument, which can come from many sources based on
  configuration in general, and meta-ata on the argument symbol and on the function's metadata (merged with
  the containing namespace's metadata).

  arg-symbol->resolver
  : From options

  resolver-factories
  : From options.

  arg-resolvers
  : From function meta-data

  route-params
  : set of keywords

  arg
  : Argument, a symbol or a map (for destructuring)."
  [arg-symbol->resolver resolver-factories arg-resolvers route-params arg]
  (let [arg-symbol (symbol-for-argument arg)
        arg-meta (meta arg-symbol)]
    (t/track #(format "Identifying argument resolver for `%s'." arg-symbol)
      (cond
        ;; Check for specific meta on the argument itself.
        (contains? arg-meta :io.aviso.rook/resolver)
        (resolver-from-metadata resolver-factories arg-symbol (:io.aviso.rook/resolver arg-meta))

        ;; Check arg-resolvers, which comes from :arg-resolvers metadata on the function or namespace
        (contains? arg-resolvers arg-symbol)
        (resolver-from-metadata resolver-factories arg-symbol (get arg-resolvers arg-symbol))

        :else (let [resolver-tag (find-argument-resolver-tag resolver-factories arg-symbol arg-meta)]

                (cond
                  resolver-tag
                  ((get resolver-factories resolver-tag) arg-symbol)

                  (contains? arg-symbol->resolver arg-symbol)
                  (get arg-symbol->resolver arg-symbol)

                  ;; Last check: does the symbol match a keyword path param
                  (contains? route-params arg-symbol)
                  (make-route-param-resolver arg-symbol)

                  :else
                  (throw (ex-info (format "Unable to identify argument resolver for symbol `%s'." arg-symbol)
                                  {:symbol           arg-symbol
                                   :symbol-meta      arg-meta
                                   :route-params     route-params
                                   :resolver-tags    (keys resolver-factories)
                                   :resolver-symbols (keys arg-symbol->resolver)}))))))))

(defn- create-arglist-resolver
  "Returns a function that is passed the Ring request and returns an array of argument values that can be applied
  to the resource handler function."
  [arg-symbol->resolver resolver-factories arg-resolvers route-params arg-list]
  (if (seq arg-list)
    (->>
      arg-list
      (map (partial identify-argument-resolver arg-symbol->resolver resolver-factories arg-resolvers (set route-params)))
      (apply juxt))
    (constantly ())))

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
   {:keys [async? arg-symbol->resolver resolver-factories]}]
  (reduce (fn [dispatch-map [[method pathvec] handler-sym]]
            (t/track #(format "Compiling handler for `%s'." (get-in handlers [handler-sym :verb-fn-sym]))
                     (let [apply-middleware (if async?
                                              apply-middleware-async
                                              apply-middleware-sync)

                           {:keys [middleware-sym route-params
                                   verb-fn-sym arglist arg-resolvers sync?
                                   metadata context]}
                           (get handlers handler-sym)

                           resolve-args (create-arglist-resolver
                                          arg-symbol->resolver
                                          resolver-factories
                                          arg-resolvers
                                          route-params
                                          arglist)

                           mw (eval (get middleware middleware-sym))

                           f (let [ef (eval verb-fn-sym)]
                               (fn wrapped-handler [request]
                                 (apply ef (resolve-args request))))

                           h (apply-middleware mw sync? f)
                           h (fn [request]
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
  {:async?               false
   :arg-symbol->resolver default-arg-symbol->resolver
   :resolver-factories   default-resolver-factories
   :build-handler-fn     build-map-traversal-handler})

(defn compile-dispatch-table
  "Compiles the dispatch table into a Ring handler.

  See the docstring of unnest-dispatch-table for a description of
  dispatch table format.

  Supported options and their default values:

  :async?
  : _Default: false_
  : Determines the way in which middleware is applied to the terminal
    handler. Pass in true when compiling async handlers.
  : Note that when async is enabled, you must be careful to only apply middleware that
    is appropriately async aware.

  :build-handler-fn
  : _Default: [[build-map-traversal-handler]]_
  : Will be called with routes, handlers, middleware and should
    produce a Ring handler.

  :arg-resolvers
  : _Default: nil_
  : Map of symbol to keyword or function; these define the argument resolver for arguments with
    the matching name. The value can be a keyword (a key in resolver-factories), or
    an argument resolver function (accept request, returns argument value).
  : :arg-resolvers metadata on a function (or namespace) is merged into this map.

  :arg-symbol->resolver
  : _Default: [[io.aviso.rook.dispatcher/default-arg-symbol->resolver]]_
  : Map from symbol to an argument resolver function.
    This is used to support the cases where an argument's name defines
    how it is resolved.
  : Unlike the :arg-resolvers key, the values here must be functions, not keywords.

  :resolver-factories
  : _Default: [[io.aviso.rook.dispatcher/default-resolver-factories]]_
  : Used to specify a map from keyword to argument resolver function _factory_. The keyword is a marker
    for meta data on the symbol (default markers include :header, :param, :injection, etc.). The value
    is a function that accepts a symbol and returns an argument resolver function (that accepts the Ring
    request and returns the argument's value)."
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

(defn- canonicalize-ns-specs
  "Handles unnesting of ns-specs. Also supplies nil in place of
  missing context-pathvec and middleware arguments."
  [outer-context-pathvec outer-middleware ns-specs]
  (mapcat (fn [[context-pathvec? ns-sym middleware? :as ns-spec]]
            (let [context-pathvec (if (vector? context-pathvec?)
                                    context-pathvec?)
                  middleware      (let [mw? (if context-pathvec
                                              middleware?
                                              ns-sym)]
                                    (if-not (vector? mw?)
                                      mw?))
                  ns-sym          (if context-pathvec
                                    ns-sym
                                    context-pathvec?)
                  skip            (reduce + 1
                                    (map #(if % 1 0)
                                      [context-pathvec middleware]))
                  nested          (drop skip ns-spec)]
              (assert (symbol? ns-sym)
                "Malformed ns-spec passed to namespace-dispatch-table")
              (concat
                [[(into outer-context-pathvec context-pathvec)
                  ns-sym
                  middleware]]
                (canonicalize-ns-specs
                  (into outer-context-pathvec context-pathvec)
                  (or middleware outer-middleware)
                  nested))))
    ns-specs))

(def ^:private default-opts
  {:context-pathvec    []
   :default-middleware identity})

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
  (let [opts         (if (map? options?)
                       (merge default-opts options?))
        ns-specs     (canonicalize-ns-specs
                       []
                       nil
                       (if opts
                         ns-specs
                         (cons options? ns-specs)))
        {outer-context-pathvec :context-pathvec
         default-middleware    :default-middleware}
        (or opts default-opts)]
    [(reduce into [outer-context-pathvec default-middleware]
       (map (fn [[context-pathvec ns-sym middleware]]
              (simple-namespace-dispatch-table
                (or context-pathvec [])
                ns-sym
                (or middleware default-middleware)))
         ns-specs))]))
