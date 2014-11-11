(ns io.aviso.rook.dispatcher
  "This namespace deals with dispatch tables mapping route specs of
  the form

      [method [path-segment ...]]

  to endpoint functions. The recognized format is described at
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

  The individual endpoint functions are expected to support a
  single arity only. The arglist for that arity and the metadata on
  the endpoint function will be examined to determine the
  correct argument resolution strategy at dispatch table compilation
  time."
  {:added "0.1.10"}
  (:import
    [java.net URLDecoder])
  (:require
    [clojure.core.async :as async]
    [clojure.string :as string]
    [clojure.set :as set]
    [io.aviso.tracker :as t]
    [io.aviso.rook.internals :as internals :refer [consume]]
    [clojure.string :as str]
    [clojure.tools.logging :as l]
    [io.aviso.rook.utils :as utils]
    [medley.core :as medley]))

(def ^:private default-mappings

  "Default function -> route spec mappings.

  Namespace dispatch tables will by default include entries for public
  Vars named by the keys in this map, with methods and routes
  provided by the values."
  {'show    [:get [:id]]
   'modify  [:put [:id]]
   ;; change will be removed at some point
   'change  [:put [:id]]
   'patch   [:patch [:id]]
   'destroy [:delete [:id]]
   'index   [:get []]
   'create  [:post []]})


(defn default-namespace-middleware
  "Default namespace middleware that ignores the metadata and returns the handler unchanged.
  Namespace middleware is slightly different than Ring middleware, as the metadata from
  the function is available. Namespace middleware may also return nil."
  [handler metadata]
  handler)

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

(def default-arg-resolvers
  "The default argument resolvers provided by the system.

  Keyword keys are factories; the value should take a symbol (from which metadata can be extracted)
  and return an argument resolver customized for that symbol.

  Symbols are direct argument resolvers; functions that take the Ring request and return the value
  for that argument."
  {:request      (constantly identity)
   :request-key  make-request-key-resolver
   :header       make-header-arg-resolver
   :param        make-param-arg-resolver
   :injection    make-injection-resolver
   :resource-uri make-resource-uri-arg-resolver
   'request      identity
   'params       (make-request-key-resolver :params)
   'params*      internals/clojurized-params-arg-resolver
   'resource-uri (make-resource-uri-arg-resolver 'resource-uri)})

(defn- merge-arg-resolver-maps
  "Merges an argument resolver map into another argument resolver map.
  The keys of each map are either keywords (for argument resolver factories) or
  symbols (for argument resolvers).

  The metadata of the override-map guides the merge.

  :replace means the override-map is used instead of the base-map.

  :replace-factories means that factories (keyword keys) from the base-map
  are removed before the merge.

  :replace-resolvers means that the resolves (symbol keys) from the base-map
  are removed before the merge."
  [base-map override-map]
  (let [override-meta (meta override-map)]

    (if (:replace override-meta)
      override-map
      (merge (cond->> base-map
                      (:replace-factories override-meta) (medley/remove-keys keyword?)
                      (:replace-resolvers override-meta) (medley/remove-keys symbol?))
             override-map))))


(defn- request-route-spec
  "Takes a Ring request map and returns `[method pathvec]`, where method
  is a request method keyword and pathvec is a vector of path
  segments.

  For example,

      GET /foo/bar HTTP/1.1

  becomes:

      [:get [\"foo\" \"bar\"]]

  The individual path segments are URL decoded; UTF-8 encoding is
  assumed."
  [request]
  [(:request-method request)
   (mapv #(URLDecoder/decode ^String % "UTF-8")
         (next (string/split (:uri request) #"/" 0)))])

(defn pathvec->path [pathvec]
  (if (seq pathvec)
    (string/join "/" (cons nil pathvec))
    "/"))

(defn route-spec->path-spec
  "Takes a route-spec in the format `[:method [\"path\" :param ...]]`
  and returns the equivalent path-spec in the format `[:method
  \"/path/:param\"]`. If passed nil as input, returns nil."
  [route-spec]
  (if-not (nil? route-spec)
    (let [[method pathvec] route-spec]
      [method (pathvec->path pathvec)])))

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
                          (consume entry
                            [context-pathvec :+
                             middleware (complement vector?) :?
                             entries :&]
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

(defn- map->sorted-routes
  "Converts the given map of route specs -> * to a sorted map."
  [routes]
  (into (sorted-map-by compare-route-specs) routes))

(defn- caching-get
  [m k f]
  (if (contains? m k)
    [m (get m k)]
    (let [new-value (f)
          new-map (assoc m k new-value)]
      [new-map new-value])))

(def ^:private suppress-metadata-keys
  "Keys to suppress when producing debugging output about function metadata; the goal is to present
  just the non-standard metadata."
  (cons :function (-> #'map meta keys)))

(defn- analyze*
  [[routes namespaces-metadata] arg-resolvers dispatch-table-entry]
  (if-let [[method pathvec verb-fn-sym endpoint-middleware] dispatch-table-entry]
    (t/track
      (format "Analyzing endpoint function `%s'." verb-fn-sym)
      (let [ns-symbol (-> verb-fn-sym namespace symbol)

            [namespaces-metadata' ns-metadata] (caching-get namespaces-metadata ns-symbol
                                                            #(binding [*ns* (the-ns ns-symbol)]
                                                              (-> *ns* meta eval (dissoc :doc))))

            metadata (merge ns-metadata
                            {:function (str verb-fn-sym)}
                            (meta (resolve verb-fn-sym)))

            _ (l/tracef "Analyzing function `%s' w/ metadata: %s"
                        (:function metadata)
                        (utils/pretty-print (apply dissoc metadata suppress-metadata-keys)))

            route-params (mapv (comp symbol name)
                               (filter keyword? pathvec))
            context (:context (meta pathvec))
            ;; Should it be an error if there is more than one airty on the function? We ignore
            ;; all but the first.
            arglist (first (:arglists metadata))
            ;; :arg-resolvers is an option passed to compile-dispatch-table,
            ;; and metadata is merged onto that.
            arg-resolvers (merge arg-resolvers (:arg-resolvers metadata))
            handler (cond->
                      {:middleware    endpoint-middleware
                       :route-params  route-params
                       :verb-fn-sym   verb-fn-sym
                       :arglist       arglist
                       :arg-resolvers arg-resolvers
                       :metadata      metadata}
                      context
                      (assoc :context (string/join "/" (cons "" context))))

            routes' (assoc routes
                           [method (keywords->symbols pathvec)] handler)]
        [routes' namespaces-metadata']))))

(defn analyse-dispatch-table
  "Returns a map holding a map of route-spec* -> handler-sym at
  key :routes, a map of route-spec -> handler-map at key :handlers and
  a map of middleware-symbol -> middleware-spec at key :middleware.
  The structure of handler-maps is as required by handler-form;
  middleware-spec is the literal form specifying the middleware in the
  dispatch table; a route-spec* is a route-spec with keywords replaced
  by symbols in the pathvec.

  Returns a sorted sequence of route data that can be passed
  to [[build-dispatch-map]].

  options should be a map of options or nil. Currently only one
  option is supported:

  :arg-resolvers

  : _Default: nil_
  : Map of symbol to argument resolver (keyword or function) that
    serves as a default that can be extended with function or
    namespace :arg-resolvers metadata. Metadata attached to this map
    will be examined; if it contains a truthy value at the key
    of :replace, default arg resolvers will be excluded."
  [dispatch-table options]
  (let [arg-resolvers (merge-arg-resolver-maps default-arg-resolvers (:arg-resolvers options))]
    (loop [analyze-state nil
           entries (seq (unnest-dispatch-table dispatch-table))]
      ;; Pass through analyze* to get the next state (or nil if everything has been processed).
      (if-let [analyze-state' (analyze* analyze-state arg-resolvers (first entries))]
        (recur analyze-state' (next entries))
        ;; Final step is to sort the routes:
        (-> analyze-state first map->sorted-routes)))))

(defn- map-traversal-dispatcher
  "Returns a Ring handler using the given dispatch-map to guide
  dispatch. Used by build-map-traversal-handler. The optional
  not-found-response argument defaults to nil; pass in a closed
  channel for async operation."
  ([dispatch-map]
   (map-traversal-dispatcher dispatch-map nil))
  ([dispatch-map not-found-response]
   (fn rook-map-traversal-dispatcher [request]
     (loop [pathvec (second (request-route-spec request))
            dispatch dispatch-map
            route-param-vals []]
       (if-let [seg (first pathvec)]
         (if (contains? dispatch seg)
           (recur (next pathvec) (get dispatch seg) route-param-vals)
           (if-let [dispatch' (::param dispatch)]
             (recur (next pathvec) dispatch' (conj route-param-vals seg))
             ;; no match on path
             not-found-response))
         (if-let [{:keys [handler route-param-keys]}
                  (or (get dispatch (:request-method request))
                      (get dispatch :all))]
           (let [route-params (zipmap route-param-keys route-param-vals)]
             (handler (assoc request :route-params route-params)))
           ;; unsupported method for path
           not-found-response))))))

(defn- symbol-for-argument [arg]
  "Returns the argument symbol for an argument; this is either the argument itself or
  (if a map, for destructuring) the :as key of the map."
  (if (map? arg)
    (if-let [as (:as arg)]
      as
      (throw (ex-info "map argument has no :as key"
                      {:arg arg})))
    arg))

(defn- find-factory [arg-resolvers tag]
  (loop [tag tag]
    (if (keyword? tag)
      (recur (get arg-resolvers tag))
      tag)))

(defn- find-resolver
  [arg-resolvers arg hint]
  (cond
    (fn? hint)
    hint

    (keyword? hint)
    (if-let [f (find-factory arg-resolvers hint)]
      (f arg)
      (throw (ex-info (format "Keyword %s does not identify a known argument resolver." hint)
                      {:arg arg :resolver hint :arg-resolvers arg-resolvers})))

    :else
    (throw (ex-info (format "Argument resolver value `%s' is neither a keyword not a function." hint)
                    {:arg arg :resolver hint}))))

(defn- find-argument-resolver-tag
  [arg-resolvers arg arg-meta]
  (let [resolver-ks (filterv #(contains? arg-resolvers %)
                             (filter keyword? (keys arg-meta)))]
    (case (count resolver-ks)
      0 nil
      1 (first resolver-ks)
      (throw (ex-info (format "Parameter `%s' has conflicting keywords identifying its argument resolution strategy: %s."
                              arg
                              (str/join ", " resolver-ks))
                      {:arg arg :resolver-tags resolver-ks})))))

(defn identify-argument-resolver
  "Identifies the specific argument resolver function for an argument, which can come from many sources based on
  configuration in general, metadata on the argument symbol and on the function's metadata (merged with
  the containing namespace's metadata).

  arg-resolvers
  : See the docstring on
    [[io.aviso.rook.dispatcher/default-arg-resolvers]]. The map passed
    to this function will have been extended with user-supplied
    resolvers and/or resolver factories.

  route-params
  : set of keywords

  arg
  : Argument, a symbol or a map (for destructuring)."
  [arg-resolvers route-params arg]
  (let [arg-symbol (symbol-for-argument arg)
        arg-meta (meta arg-symbol)]
    (t/track #(format "Identifying argument resolver for `%s'." arg-symbol)
             (cond
               ;; route param resolution takes precedence
               (contains? route-params arg)
               (make-route-param-resolver arg-symbol)

               ;; explicit ::rook/resolver metadata takes precedence for non-route params
               (contains? arg-meta :io.aviso.rook/resolver)
               (find-resolver arg-resolvers arg-symbol (:io.aviso.rook/resolver arg-meta))

               :else
               (if-let [resolver-tag (find-argument-resolver-tag
                                       arg-resolvers arg-symbol arg-meta)]
                 ;; explicit tags attached to the arg symbol itself come next
                 (find-resolver arg-resolvers arg-symbol resolver-tag)

                 ;; non-route-param name-based resolution is implicit and
                 ;; should not override explicit tags, so this check comes
                 ;; last; NB. the value at arg-symbol might be a keyword
                 ;; identifying a resolver factory, so we still need to call
                 ;; find-resolver
                 (if (contains? arg-resolvers arg-symbol)
                   (find-resolver arg-resolvers arg-symbol (get arg-resolvers arg-symbol))

                   ;; only static resolution is supported
                   (throw (ex-info
                            (format "Unable to identify argument resolver for symbol `%s'." arg-symbol)
                            {:symbol        arg-symbol
                             :symbol-meta   arg-meta
                             :route-params  route-params
                             :arg-resolvers arg-resolvers}))))))))

(defn- create-arglist-resolver
  "Returns a function that is passed the Ring request and returns an array of argument values which
  the endpoint function can be applied to."
  [arg-resolvers route-params arglist]
  (if (seq arglist)
    (->>
      arglist
      (map (partial identify-argument-resolver arg-resolvers (set route-params)))
      (apply juxt))
    (constantly ())))

(defn- add-dispatch-entries
  [dispatch-map method pathvec handler]
  (let [pathvec' (mapv #(if (variable? %) ::param %) pathvec)
        dispatch-path (conj pathvec' method)
        route-params (filterv variable? pathvec)]
    (assoc-in dispatch-map dispatch-path
              {:handler          handler
               :route-param-keys (mapv keyword route-params)})))

(defn- build-dispatch-map
  "Returns a dispatch-map for use with map-traversal-dispatcher."
  [sorted-routes {:keys [async? arg-resolvers sync-wrapper]}]
  (reduce (fn [dispatch-map [[method pathvec] handler]]
            (t/track
              #(format "Compiling handler for `%s'." (:verb-fn-sym handler))
              (let [{:keys [middleware route-params
                            verb-fn-sym arglist
                            metadata context]} handler

                    endpoint-arg-resolvers (merge-arg-resolver-maps arg-resolvers (:arg-resolvers handler))

                    arglist-resolver (create-arglist-resolver
                                       endpoint-arg-resolvers
                                       (set route-params)
                                       arglist)

                    ;; middleware functions may return nil, so:

                    middleware' (fn [handler metadata]
                                  (or (middleware handler metadata)
                                      handler))

                    apply-context-middleware (fn [handler]
                                               (fn [request]
                                                 (-> request
                                                     (update-in [:context] str context)
                                                     handler)))

                    logging-middleware (fn [handler]
                                         (fn [request]
                                           (l/debugf "Matched %s to %s"
                                                     (utils/summarize-request request)
                                                     verb-fn-sym)
                                           (handler request)))

                    endpoint-fn (eval verb-fn-sym)

                    ;; Build up a Ring request handler from the Rook endpoint and middleware.
                    request-handler (cond-> (fn [request]
                                              (apply endpoint-fn (arglist-resolver request)))

                                            (and async? (:sync metadata))
                                            (sync-wrapper metadata)

                                            :always
                                            (middleware' metadata)

                                            context
                                            apply-context-middleware

                                            :always
                                            logging-middleware)]

                (add-dispatch-entries dispatch-map method pathvec request-handler))))
          {}
          sorted-routes))

(defn- build-map-traversal-handler
  "Returns a Ring handler that handles dispatch
  by using the pathvec and method of the incoming request to look up
  an endpoint Ring handler in a nested map."
  [sorted-routes opts]
  (let [dispatch-map (build-dispatch-map sorted-routes opts)]
    (if (:async? opts)
      (map-traversal-dispatcher dispatch-map
                                (doto (async/chan) (async/close!)))
      (map-traversal-dispatcher dispatch-map))))

(def ^:private dispatch-table-compilation-defaults
  {:async?        false
   :arg-resolvers default-arg-resolvers
   :sync-wrapper  (fn [handler metadata]
                    (internals/ring-handler->async-handler handler))})

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

  :sync-wrapper
  : _Default: anonymous_
  : Converts a synchronous request handler into
    an asynchronous handler; this is only used in async mode, when the endpoint
    function has the :sync metadata. The value is an async Rook middleware
    (passed the request handler, and the endpoint function's metadata).


  :arg-resolvers
  : _Default: [[io.aviso.rook.dispatcher/default-arg-resolvers]]_
  : Map of symbol to (keyword or function of request) or keyword
    to (function of symbol returning function of request). Entries of
    the former provide argument resolvers to be used when resolving
    arguments named by the given symbol; in the keyword case, a known
    resolver factory will be used. Entries of the latter type
    introduce custom resolver factories. Tag with {:replace true} to
    exclude default resolvers and resolver factories; tag with
    {:replace-resolvers true} or {:replace-factories true} to leave
    out default resolvers or resolver factories, respectively."
  ([dispatch-table]
   (compile-dispatch-table nil dispatch-table))
  ([options dispatch-table]
   (let [options (merge dispatch-table-compilation-defaults options)
         sorted-routes (analyse-dispatch-table dispatch-table options)]
     (build-map-traversal-handler sorted-routes options))))

(defn- simple-namespace-dispatch-table
  "Examines the given namespace and produces a dispatch table in a
  format intelligible to compile-dispatch-table."
  ([ns-sym]
   (simple-namespace-dispatch-table [] ns-sym))
  ([context-pathvec ns-sym]
   (simple-namespace-dispatch-table context-pathvec ns-sym default-namespace-middleware))
  ([context-pathvec ns-sym middleware]
   (t/track
     #(format "Identifying endpoint functions in `%s'." ns-sym)
     (try
       (if-not (find-ns ns-sym)
         (require ns-sym))
       (catch Exception e
         (throw (ex-info "failed to require ns in namespace-dispatch-table"
                         {:context    context-pathvec
                          :ns         ns-sym
                          :middleware middleware}
                         e))))
     [(->> ns-sym
           ns-publics
           (keep (fn [[k v]]
                   (if (ifn? @v)
                     (t/track #(format "Building route mapping for `%s/%s'." ns-sym k)
                              (if-let [route-spec (or (-> v meta :route)
                                                      (get default-mappings k))]
                                (conj route-spec (symbol (name ns-sym) (name k))))))))
           (list* context-pathvec middleware)
           vec)])))

(defn canonicalize-ns-specs
  "Handles unnesting of ns-specs."
  [outer-context-pathvec outer-middleware ns-specs]
  (mapcat (fn [ns-spec]
            (t/track
              #(format "Parsing namespace specification `%s'." (pr-str ns-spec))
              (consume ns-spec
                [context #(or (nil? %) (vector? %) (string? %)) :?
                 ns-sym symbol? 1
                 middleware fn? :?
                 nested :&]
                (let [context-pathvec (if (string? context)
                                        (vector context)
                                        (or context []))]
                  (concat
                    [[(into outer-context-pathvec context-pathvec)
                      ns-sym
                      (or middleware outer-middleware)]]
                    (canonicalize-ns-specs
                      (into outer-context-pathvec context-pathvec)
                      (or middleware outer-middleware)
                      nested))))))
          ns-specs))

(def ^:private default-opts
  {:context            []
   :default-middleware default-namespace-middleware})

(defn namespace-dispatch-table
  "Similar to [[io.aviso.rook/namespace-handler]], but stops short of
  producing a handler, returning a dispatch table instead. See the
  docstring of [[io.aviso.rook/namespace-handler]] for a description
  of ns-spec syntax and a list of supported options (NB. `async?` is
  irrelevant to the shape of the dispatch table).

  The resulting dispatch table in its unnested form will include
  entries such as

      [:get [\"api\" \"foo\"] 'example.foo/index ns-middleware]."
  {:arglists '([options & ns-specs]
               [& ns-specs])}
  [& &ns-specs]
  (consume &ns-specs
    [options map? :?
     ns-specs :&]
    (let [{outer-context-pathvec :context
           default-middleware    :default-middleware} (merge default-opts options)
          ns-specs' (canonicalize-ns-specs
                      []
                      default-middleware
                      ns-specs)]
      [(reduce into [outer-context-pathvec default-middleware]
               (map (fn [[context-pathvec ns-sym middleware]]
                      (simple-namespace-dispatch-table
                        context-pathvec ns-sym middleware))
                    ns-specs'))])))
