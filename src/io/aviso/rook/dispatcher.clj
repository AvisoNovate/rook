(ns io.aviso.rook.dispatcher
  (:require [clojure.core.match :refer [match]]
            [clojure.string :as string]
            [clojure.pprint :as pp]
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
  (pp/write form :dispatch pp/code-dispatch))

(defn preparse-request
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

  In which case pathvec and middleware? (if present) will provide a
  context pathvec and default middleware for the nested entries
  without introducing a separate route."
  [dispatch-table]
  (letfn [(unnest-entry [default-middleware [x :as entry]]
            (cond
              (keyword? x)
              (let [[method pathvec verb-fn middleware & nested-table] entry]
                (if nested-table
                  (into [[method pathvec verb-fn
                          (or middleware default-middleware)]]
                    (unnest-table pathvec nested-table))
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
  "Used by compile-dispatch-table."
  [request-sym arglist route-params non-route-params]
  (mapcat (fn [param]
            [param
             (if (contains? route-params param)
               `(get (:route-params ~request-sym) ~(keyword param))
               `(internals/extract-argument-value
                  (keyword (quote ~param))
                  ~request-sym
                  (-> ~request-sym :rook :arg-resolvers)))])
    arglist))

(defn apply-middleware-sync [middleware sync? handler]
  (middleware handler))

(defn apply-middleware-async [middleware sync? handler]
  (middleware
    (if sync?
      (rook-async/ring-handler->async-handler handler)
      handler)))

(defn compile-dispatch-table
  "Compiles the dispatch table into a Ring handler.

  See the docstring of unnest-dispatch-table for a description of
  dispatch table format."
  ([dispatch-table]
     (compile-dispatch-table {} dispatch-table))
  ([options dispatch-table]
     (let [dt  (unnest-dispatch-table dispatch-table)
           req (gensym "request__")
           emit-fn (:emit-fn options eval)
           apply-middleware (:apply-middleware-fn options
                                                  `apply-middleware-sync)]
       (emit-fn
         `(fn rook-dispatcher# [~req]
            (match (preparse-request ~req)
              ~@(mapcat
                  (fn [[method pathvec verb-fn-sym middleware]]
                    (let [ns-metadata      (-> verb-fn-sym
                                             namespace symbol the-ns meta)
                          metadata         (merge (dissoc ns-metadata :doc)
                                             (meta (resolve verb-fn-sym)))
                          sync?            (:sync metadata)
                          pathvec          (keywords->symbols pathvec)
                          route-params     (set (filter symbol? pathvec))
                          arglist          (first (:arglists metadata))
                          non-route-params (remove route-params arglist)
                          arg-resolvers    (:arg-resolvers metadata)
                          add-resolvers    (if arg-resolvers
                                             `(rook/wrap-with-arg-resolvers
                                                ~@arg-resolvers))
                          middleware       (if arg-resolvers
                                             `(fn [handler#]
                                                (-> handler#
                                                  ~add-resolvers
                                                  ~middleware))
                                             middleware)]
                      [[method pathvec]
                       `(let [route-params# ~(zipmap
                                               (map keyword route-params)
                                               route-params)
                              handler# (fn [~req]
                                         (let [~@(prepare-handler-bindings
                                                   req
                                                   arglist
                                                   route-params
                                                   non-route-params)]
                                           (~verb-fn-sym ~@arglist)))]
                          ((~apply-middleware ~middleware ~sync? handler#)
                           (assoc ~req
                             :route-params route-params#)))]))
                  dt)
              :else nil))))))

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
     (namespace-dispatch-table context-pathvec ns-sym default-middleware))
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
     (let [context-pathvec ((fnil conj [])
                            context-pathvec
                            (peek (string/split (name ns-sym) #"\.")))]
       [(->> ns-sym
          ns-publics
          (keep (fn [[k v]]
                  (if (ifn? @v)
                    (if-let [route-spec (or (:route-spec (meta v))
                                            (get default-mappings k))]
                      (conj route-spec (symbol (name ns-sym) (name k)))))))
          (list* context-pathvec middleware)
          vec)])))
