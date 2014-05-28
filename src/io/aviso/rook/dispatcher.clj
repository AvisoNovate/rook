(ns io.aviso.rook.dispatcher
  (:require [clojure.core.match :refer [match]]
            [clojure.string :as string]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [io.aviso.rook :as rook]
            [io.aviso.rook.async :as rook-async]
            [io.aviso.rook.internals :as internals]
            [io.aviso.rook.schema-validation :as sv]))

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

(defn pprint-code [form]
  (pp/write form :dispatch pp/code-dispatch)
  (prn))

(defn request-route-spec
  "Takes a Ring request map and returns [method pathvec], where method
  is a request method keyword and pathvec is a vector of path
  segments.

  For example,

    GET /foo/bar HTTP/1.1

  becomes

    [:get [\"foo\" \"bar\"]].

  The individual path segments are URL decoded; UTF-8 encoding is
  assumed."
  [request]
  [(:request-method request)
   (mapv #(java.net.URLDecoder/decode ^String % "UTF-8")
     (next (string/split (:uri request) #"/" 0)))])

(defn unnest-dispatch-table
  "Given a nested dispatch table:

    [[method pathvec verb-fn middleware
      [method' pathvec' verb-fn' middleware' ...]
      ...]
     ...]

  produces a dispatch table with no nesting:

    [[method pathvec verb-fn middleware]
     [method' (into pathvec pathvec') verb-fn' middleware']
     ...].

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
                    (assoc unnested-entry 1 (into context-pathvec pathvec)))
              (mapcat (partial unnest-entry default-middleware) entries)))]
    (unnest-table [] nil dispatch-table)))

(defn keywords->symbols
  "Converts keywords in xs to symbols, leaving other items unchanged."
  [xs]
  (mapv #(if (keyword? %)
           (symbol (name %))
           %)
    xs))

(defn prepare-handler-bindings
  "Used by handler-form."
  [request-sym arglist route-params non-route-params]
  (let [route-param? (set route-params)]
    (mapcat (fn [param]
              [param
               (if (route-param? param)
                 `(get (:route-params ~request-sym) ~(keyword param))
                 `(internals/extract-argument-value
                    ~(keyword param)
                    ~request-sym
                    (-> ~request-sym :rook :arg-resolvers)))])
      arglist)))

(defn apply-middleware-sync [middleware sync? handler]
  (middleware handler))

(defn apply-middleware-async [middleware sync? handler]
  (middleware
    (if sync?
      (rook-async/ring-handler->async-handler handler)
      handler)))

(defn variable? [x]
  (or (keyword? x) (symbol? x)))

(defn compare-pathvecs [pathvec1 pathvec2]
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

(defn compare-route-specs [[method1 pathvec1] [method2 pathvec2]]
  (let [res (compare-pathvecs pathvec1 pathvec2)]
    (if (zero? res)
      (compare method1 method2)
      res)))

(defn sort-dispatch-table [dispatch-table]
  (vec (sort compare-route-specs dispatch-table)))

(defn sorted-routes [routes]
  (into (sorted-map-by compare-route-specs) routes))

(defn analyse-dispatch-table
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
            method      (if (identical? method :all) '_ method)
            routes      (assoc routes
                          [method (keywords->symbols pathvec)] handler-sym)
            [middleware mw-sym]
            (if (contains? middleware mw-spec)
              [middleware (get middleware mw-spec)]
              (let [mw-sym (gensym "mw_sym__")]
                [(assoc middleware mw-spec mw-sym) mw-sym]))
            ns-metadata      (-> verb-fn-sym namespace symbol the-ns meta)
            metadata         (merge (dissoc ns-metadata :doc)
                               (meta (resolve verb-fn-sym)))
            sync?            (:sync metadata)
            route-params     (mapv (comp symbol name)
                               (filter keyword? pathvec))
            pathvec          (keywords->symbols pathvec)
            arglist          (first (:arglists metadata))
            non-route-params (remove (set route-params) arglist)
            arg-resolvers    (:arg-resolvers metadata)
            schema           (:schema metadata)
            handler  {:middleware-sym   mw-sym
                      :route-params     route-params
                      :non-route-params non-route-params
                      :verb-fn-sym      verb-fn-sym
                      :arglist          arglist
                      :arg-resolvers    arg-resolvers
                      :schema           schema
                      :sync?            sync?}
            handlers (assoc handlers handler-sym handler)]
        (recur routes handlers middleware (next entries)))
      {:routes     (sorted-routes routes)
       :handlers   handlers
       :middleware (set/map-invert middleware)})))

(defn wrap-with-schema [handler schema]
  (fn [request]
    (handler (assoc-in request [:rook :metadata :schema] schema))))

(defn handler-form
  "Returns a Clojure form evaluating to a handler wrapped in middleware.
  The middleware stack includes the specified arg-resolvers, if any,
  in the innermost position, and the middleware specified by
  middleware-sym. The resulting handler calls the function named by
  verb-fn-sym with arguments extracted from the request map."
  [apply-middleware req handler-sym
   {:keys [middleware-sym
           route-params
           non-route-params
           verb-fn-sym
           arglist
           arg-resolvers
           schema
           sync?]}]
  (let [prewrap-handler-sym (gensym (str handler-sym "__prewrap_handler__"))
        wrapped-mw-sym      (gensym (str middleware-sym "__wrapped__"))]
    `(~apply-middleware
       ~(if (or (seq arg-resolvers) schema)
          `(fn ~wrapped-mw-sym [handler#]
             (-> handler#
               ~@(if (seq arg-resolvers)
                   [`(rook/wrap-with-arg-resolvers ~@arg-resolvers)])
               ~middleware-sym
               ~@(if schema
                   [`(wrap-with-schema ~schema)])))
          middleware-sym)
       ~sync?
       (fn ~prewrap-handler-sym [~req]
         (let [~@(prepare-handler-bindings
                   req
                   arglist
                   route-params
                   non-route-params)]
           (~verb-fn-sym ~@arglist))))))

(defn build-pattern-matching-handler
  "Returns a form evaluating to a Ring handler using pattern matching
  on the request pathvec to select the appropriate endpoint function."
  [routes handlers middleware apply-middleware]
  (let [req (gensym "request__")]
    `(let [~@(apply concat middleware)
           ~@(mapcat (fn [[handler-sym handler-map]]
                       [handler-sym
                        (handler-form
                          apply-middleware req handler-sym handler-map)])
               handlers)]
       (fn rook-pattern-matching-dispatcher# [~req]
         (match (request-route-spec ~req)
           ~@(mapcat (fn [[route-spec handler-sym]]
                       (let [{:keys [route-params schema]}
                             (get handlers handler-sym)]
                         [route-spec
                          `(let [route-params#
                                 ~(zipmap
                                    (map keyword route-params)
                                    route-params)]
                             (~handler-sym
                               (assoc ~req :route-params route-params#)))]))
               routes)
           :else nil)))))

(defn build-map-traversal-handler
  "Returns a form evaluating to a Ring handler that handles dispatch
  by using the pathvec and method of the incoming request to look up
  an endpoint function in a nested map."
  [routes handlers middleware apply-middleware]
  (let [req (gensym "request__")
        tmp (gensym "tmp_var__")]
    `(do
       (def ~tmp
         (atom {:dispatch-map {}
                :endpoint-fns {}
                :middleware   {}}))
       ~@(map (fn [[mw-sym mw]]
                `(swap! ~tmp assoc-in [:middleware '~mw-sym] ~mw))
           middleware)
       ~@(mapcat
           (fn [[[method pathvec] handler-sym]]
             (let [handler-map (get handlers handler-sym)
                   mw-sym      (:middleware-sym handler-map)
                   schema      (:schema handler-map)
                   h `(let [~mw-sym (get-in @~tmp [:middleware '~mw-sym])]
                        ~(handler-form
                           apply-middleware req handler-sym handler-map))
                   dispatch-path (conj
                                   (mapv (fn [seg]
                                           (if (variable? seg)
                                             ::param-next
                                             seg))
                                     pathvec)
                                   method)
                   binding-names (filter variable? pathvec)
                   binding-paths (keep-indexed
                                   (fn [i seg]
                                     (if (variable? seg)
                                       (-> (subvec pathvec 0 i)
                                         (into [])
                                         (conj ::param-name))))
                                   pathvec)]
               `[(swap! ~tmp assoc-in [:endpoint-fns '~handler-sym] ~h)
                 (swap! ~tmp assoc-in
                   (into [:dispatch-map] ~dispatch-path)
                   '~handler-sym)
                 ~@(map (fn [name path]
                          `(swap! ~tmp
                             assoc-in
                             (into [:dispatch-map] ~path) ~(keyword name)))
                     binding-names
                     binding-paths)]))
           routes)
       (let [dispatch-map# (:dispatch-map @~tmp)
             endpoint-fns# (:endpoint-fns @~tmp)]
         (ns-unmap *ns* '~tmp)
         (fn rook-map-traversal-dispatcher# [~req]
           [dispatch-map# endpoint-fns#]
           (loop [pathvec#      (second (request-route-spec ~req))
                  dispatch#     dispatch-map#
                  route-params# {}]
             (if-let [seg# (first pathvec#)]
               (if (contains? dispatch# seg#)
                 (recur (next pathvec#) (get dispatch# seg#) route-params#)
                 (if-let [v# (::param-name dispatch#)]
                   (recur (next pathvec#) (::param-next dispatch#)
                     (assoc route-params# v# seg#))
                   ;; no match on path
                   nil))
               (if-let [h# (get endpoint-fns#
                             (get dispatch# (:request-method ~req)))]
                 (h# (assoc ~req :route-params route-params#))
                 ;; unsupported method for path
                 nil))))))))

(def dispatch-table-compilation-defaults
  {:emit-fn             eval
   :apply-middleware-fn `apply-middleware-sync
   :build-handler-fn    build-pattern-matching-handler})

(defn compile-dispatch-table
  "Compiles the dispatch table into a Ring handler.

  See the docstring of unnest-dispatch-table for a description of
  dispatch table format."
  ([dispatch-table]
     (compile-dispatch-table
       dispatch-table-compilation-defaults
       dispatch-table))
  ([options dispatch-table]
     (let [options          (merge dispatch-table-compilation-defaults options)
           emit-fn          (:emit-fn options)
           apply-middleware (:apply-middleware-fn options)
           build-handler    (:build-handler-fn options)

           {:keys [routes handlers middleware]}
           (analyse-dispatch-table dispatch-table)]
       (emit-fn
         (build-handler routes handlers middleware apply-middleware)))))

(defn default-middleware [handler]
  (-> handler
    rook/wrap-with-function-arg-resolvers
    sv/wrap-with-schema-validation))

(defn namespace-dispatch-table
  "Examines the given namespace and produces a dispatch table in a
  format intelligible to compile-dispatch-table."
  ([ns-sym]
     (namespace-dispatch-table [] ns-sym))
  ([context-pathvec ns-sym]
     (namespace-dispatch-table context-pathvec ns-sym `default-middleware))
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
                                          (get default-mappings k))]
                    (conj route-spec (symbol (name ns-sym) (name k)))))))
        (list* context-pathvec middleware)
        vec)]))
