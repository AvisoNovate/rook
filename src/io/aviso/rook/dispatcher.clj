(ns io.aviso.rook.dispatcher
  "The heart of Rook's dispatch logic, converting namespace specifications into a Ring request handler.

  The main function is [[construct-namespace-handler]], but this is normally only invoked
  by [[namespace-handler]]."
  {:added "0.1.10"}
  (:require [clojure.string :as string]
            [io.aviso.tracker :as t]
            [io.aviso.toolchest.macros :refer [consume cond-let]]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [io.aviso.toolchest.exceptions :refer [to-message]]
            [io.aviso.rook.internals :as internals]
            [clojure.string :as str]
            [clojure.tools.logging :as l]
            [io.aviso.rook.utils :as utils]
            [medley.core :as medley])
  (:import [java.net URLDecoder]))

(def supported-methods
  "The supported methods, used to \"split\" a route with method :all."
  #{:get :put :post :patch :delete :head :options})

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

(defn- require-port?
  [scheme port]
  (case scheme
    :http (not (= port 80))
    :https (not (= port 443))
    true))

(defn ^:no-doc resource-uri-for
  "An argument resolver that assembles the details in the request to form the qualified URI for the namespace."
  [request]
  (let [server-uri (or (:server-uri request)
                       (str (-> request :scheme name)
                            "://"
                            (-> request :server-name)
                            (let [port (-> request :server-port)]
                              (if (require-port? (:scheme request) port)
                                (str ":" port)))))
        context (::context request)]
    (str server-uri
         "/"
         context
         ;; Add a seperator after the context, unless it is blank
         (if-not (str/blank? context) "/"))))

(defn default-namespace-middleware
  "Default endpoint middleware that ignores the metadata and returns the handler unchanged.
  Endpoint middleware is slightly different than Ring middleware, as the metadata from
  the function is available. Endpoint middleware may also return nil."
  [handler metadata]
  handler)

(defn- make-header-arg-resolver [sym]
  (let [header-name (name sym)]
    (with-meta
      (fn [request]
                 (-> request :headers (get header-name)))
      ;; Needed by the Swagger support to identify that a argument is derived from a specific header.
      {:header-name header-name})))

(defn- make-param-arg-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request :params kw))))

(defn- make-request-key-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (kw request))))

(defn- make-route-param-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (-> request ::route-params kw))))

(defn- make-resource-uri-arg-resolver [sym]
  (fn [request]
    (resource-uri-for request)))

(defn- make-injection-resolver [sym]
  (let [kw (keyword sym)]
    (fn [request]
      (internals/get-injection request kw))))

(defn- to-clojureized-keyword
  "Converts a keyword with embedded underscores into one with embedded dashes."
  [kw]
  (-> kw
      name
      (.replace \_ \-)
      keyword))

(defn- clojurized-params-arg-resolver [request]
  (->> request
       :params
       (medley/map-keys to-clojureized-keyword)))

(def default-arg-resolvers
  "The default argument resolvers provided by the system.

  Keyword keys are factories; the value should take a symbol (from which metadata can be extracted)
  and return an argument resolver customized for that symbol.

  Symbols are direct argument resolvers; functions that take the Ring request and return the value
  for that argument."
  (let [params-resolver (make-request-key-resolver :params)]
    {:request      (constantly identity)
     :request-key  make-request-key-resolver
     :header       make-header-arg-resolver
     :param        make-param-arg-resolver
     :injection    make-injection-resolver
     :resource-uri make-resource-uri-arg-resolver
     'request      identity
     'params       params-resolver
     '_params      params-resolver
     'params*      clojurized-params-arg-resolver
     '_params*     clojurized-params-arg-resolver
     'resource-uri (make-resource-uri-arg-resolver 'resource-uri)}))

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
  (cond-let

    (nil? override-map)
    base-map

    [override-meta (meta override-map)]

    (:replace override-meta)
    override-map

    :else
    (merge (cond->> base-map
                    (:replace-factories override-meta) (medley/remove-keys keyword?)
                    (:replace-resolvers override-meta) (medley/remove-keys symbol?))
           override-map)))


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
      (throw (ex-info (format "Argument `%s' has conflicting keywords identifying its resolution strategy: %s."
                              arg
                              (str/join ", " resolver-ks))
                      {:arg arg :resolver-tags resolver-ks})))))

(defn- identify-argument-resolver
  "Identifies the specific argument resolver function for an argument, which can come from many sources based on
  configuration in general, metadata on the argument symbol and on the function's metadata (merged with
  the containing namespace's metadata).

  arg-resolvers
  : See the docstring on
    [[io.aviso.rook.dispatcher/default-arg-resolvers]]. The map passed
    to this function will have been extended with user-supplied
    resolvers and/or resolver factories.

  route-params
  : set of symbols (converted from keywords in the endpoint's route)

  arg
  : Argument, a symbol."
  [arg-resolvers route-params arg]
  (let [arg-meta (meta arg)]
    (t/track #(format "Identifying argument resolver for `%s'." arg)
             (cond
               ;; route param resolution takes precedence
               (contains? route-params arg)
               (make-route-param-resolver arg)

               ;; explicit ::rook/resolver metadata takes precedence for non-route params
               (contains? arg-meta :io.aviso.rook/resolver)
               (find-resolver arg-resolvers arg (:io.aviso.rook/resolver arg-meta))

               :else
               (if-let [resolver-tag (find-argument-resolver-tag
                                       arg-resolvers arg arg-meta)]
                 ;; explicit tags attached to the arg symbol itself come next
                 (find-resolver arg-resolvers arg resolver-tag)

                 ;; non-route-param name-based resolution is implicit and
                 ;; should not override explicit tags, so this check comes
                 ;; last; NB. the value at arg-symbol might be a keyword
                 ;; identifying a resolver factory, so we still need to call
                 ;; find-resolver
                 (if (contains? arg-resolvers arg)
                   (find-resolver arg-resolvers arg (get arg-resolvers arg))

                   ;; only static resolution is supported
                   (throw (ex-info
                            (format "Unable to identify argument resolver for symbol `%s'." arg)
                            {:symbol        arg
                             :symbol-meta   arg-meta
                             :route-params  route-params
                             :arg-resolvers arg-resolvers}))))))))

(defn- identify-arglist-resolvers
  "Returns a function that is passed the Ring request and returns an array of argument values which
  the endpoint function can be applied to."
  [arg-resolvers route-params arglist]
  (map (partial identify-argument-resolver arg-resolvers route-params) arglist))

(defn- expand-context
  [context]
  (cond
    (nil? context)
    []

    (string? context)
    [context]

    (vector? context)
    context

    :else
    (throw (ex-info "Unexpected namespace context (should be nil, string or vector)."
                    {:context context}))))


(def ^:private suppress-metadata-keys
  "Keys to suppress when producing debugging output about function metadata; the goal is to present
  just the non-standard metadata. :route is left in (whether explicit, or added by naming
  convention)."
  (cons :function (-> #'map meta keys)))

(defn ^:no-doc build-namespace-table
  "Returns a seq of expanded ns-specs.

  The input ns-specs are seq of recursive structures:

    [context ns-symbol arg-resolvers? middleware? & nested-ns-specs]

  The output ns-specs are flattened and complete:

  [[context ns-symbol arg-resolvers middleware]]

  In the flattened/complete version, the context of child namespaces are prefixed
  with the context of parent namespaces.  The arg-resolvers and middleware are always
  present, with arg-resolvers representing the merge of the child namespace's arg-resolvers
  with the parent's."
  [root-context default-arg-resolvers default-middleware ns-specs]
  (loop [result (transient [])
         remaining ns-specs]
    (if (empty? remaining)
      (persistent! result)
      (consume (first remaining)
               [context #(or (nil? %) (vector? %) (string? %)) :?
                ns-sym symbol? 1
                arg-resolvers map? :?
                middleware fn? :?
                nested :&]
               (let [namespace-context (concat root-context (expand-context context))
                     namespace-arg-resolvers (merge-arg-resolver-maps default-arg-resolvers arg-resolvers)
                     namespace-middleware (or middleware default-middleware)
                     nested' (if (seq nested)
                               (build-namespace-table namespace-context namespace-arg-resolvers namespace-middleware nested)
                               [])]
                 (recur
                   (as-> result $
                         (conj! $ [namespace-context ns-sym namespace-arg-resolvers namespace-middleware])
                         (reduce conj! $ nested'))
                   (rest remaining)))))))

(defn- evaluate-namespace-metadata
  [context ns-sym]
  (t/track
    #(format "Loading namespace `%s'." ns-sym)
    (try
      (if-not (find-ns ns-sym)
        (require ns-sym))
      (catch Exception e
        (throw (ex-info (format "Failed to require namespace `%s': %s"
                                ns-sym
                                (to-message e))
                        {:context   context
                         :namespace ns-sym}
                        e))))

    (t/track
      #(format "Evaluating metadata for namespace `%s'." ns-sym)
      (binding [*ns* (the-ns ns-sym)]
        (-> *ns* meta eval)))))

(defn- expand-namespace-metadata
  "Adds a fifth element to each ns-spec, the evaluated namespace meta-data"
  [ns-specs]
  (map (fn [[context ns-sym :as ns-spec]]
         (conj ns-spec (evaluate-namespace-metadata context ns-sym)))
       ns-specs))

(defn- construct-namespace-table-entry
  [container-context ns-sym arg-resolvers middleware ns-metadata fn-name fn-var]
  (t/track
    #(format "Building mapping for `%s/%s'." ns-sym fn-name)
    (let
      [fn-meta (meta fn-var)
       [method path :as route] (or (:route fn-meta)
                                   (get default-mappings fn-name))]

      (when (some? route)

        (if-not (or (= :all method)
                    (supported-methods method))
          (throw (ex-info (format "HTTP method %s is not supported. Supported methods are: %s, or :all (to match regardless of method)."
                                  method
                                  (->> supported-methods sort (str/join ", ")))
                          {:function fn-name})))

        (let [endpoint-name (str ns-sym "/" fn-name)
              merged-metadata (merge ns-metadata
                                     {:function endpoint-name
                                      :route    route}
                                     fn-meta)

              _ (l/tracef "Analyzing function `%s' w/ metadata: %s"
                          endpoint-name
                          (pretty-print (apply dissoc merged-metadata suppress-metadata-keys)))

              endpoint-context (vec (concat container-context path))

              endpoint-arg-resolvers (merge-arg-resolver-maps arg-resolvers
                                                              (:arg-resolvers merged-metadata))

              arglist (->> fn-meta :arglists first (map symbol-for-argument))

              route-params (->> endpoint-context
                                (filter keyword?)
                                (map (comp symbol name))
                                set)

              arglist-resolvers (identify-arglist-resolvers endpoint-arg-resolvers
                                                             route-params
                                                             arglist)

              merged-metadata' (assoc merged-metadata
                                 ;; Some extra data, especially useful for the Swagger support.
                                 ;; seq of symbols:
                                 :arguments arglist
                                 ;; map from symbol to resolver function:
                                 :argument-resolvers (zipmap arglist arglist-resolvers))

              arglist-resolver (if (empty? arglist-resolvers)
                                 (constantly ())
                                 (apply juxt arglist-resolvers))

              context-length (count container-context)

              apply-context-middleware (fn [handler]
                                         (fn [request]
                                           (-> request
                                               (assoc ::context (->> request ::path (take context-length) (str/join "/")))
                                               handler)))

              logging-middleware (fn [handler]
                                   (fn [request]
                                     (l/debugf "Matched %s to %s"
                                               (utils/summarize-request request)
                                               endpoint-name)
                                     (handler request)))

              endpoint-fn @fn-var

              ;; This ensures that even when middleware returns nil, the handler is non-nil.
              full-middleware (internals/compose-middleware middleware)

              request-handler (-> (fn [request]
                                    (apply endpoint-fn (arglist-resolver request)))

                                  (full-middleware merged-metadata')

                                  apply-context-middleware
                                  logging-middleware)]
          [method endpoint-context request-handler merged-metadata'])))))

(defn- expand-namespace-entry
  "Expands on entry from the output of [[build-namespace-table]] and [[expand-namespace-metadata]],
  converting each namespace entry into
  a seq of routing entries:

      [method path handler endpoint-meta]

  method
  : keyword for the HTTP method (:get, :put, etc., or :all)

  path
  : seq of path terms (each is a string or a keyword)

  handler
  : a Ring request handler derived from an endpoint function of the namespace,
    that has been intercepted to include middleware, argument resolution, etc.

  endpoint-meta
  : Merged meta-data for the handler, including key :function (namespace qualified name of the function)"
  [[context ns-sym arg-resolvers middleware ns-metadata]]
  (t/track
    #(format "Identifying endpoints in namespace `%s'." ns-sym)
    (let [ns-metadata' (dissoc ns-metadata :doc)
          arg-resolvers' (merge-arg-resolver-maps arg-resolvers (:arg-resolvers ns-metadata'))]
      (->> ns-sym
           ns-publics
           (keep (fn [[k v]]
                   (if (ifn? @v)
                     (construct-namespace-table-entry context ns-sym arg-resolvers' middleware ns-metadata' k v))))
           doall))))

(def ^:private conj' (fnil conj []))

(defn- add-handler-to-dispatch-map [dispatch-map path method handler endpoint-meta route-params]
  (update-in dispatch-map (conj path method)
             conj' [handler endpoint-meta route-params]))

(defn- construct-dispatch-map
  "Constructs the dispatch map from routing specs (created by
  [[construct-routing-table]]. The structure of the dispatch map
  is a tree. Each node on the tree is a map.

  String keys in a node are literal terms in the path, the value is a nested node.

  The :_ key in a node represents a position of a path argument, the value is a nested node.

  The other keys in a node are request methods (:get, :put, etc., or :all). The value
  is a vector of handler data, each of which is a vector of [handler endpoint-meta route-params]."
  [routing-specs]
  (reduce
    (fn [dispatch-map [method path handler endpoint-meta]]
      (let [path' (mapv #(if (keyword? %) :_ %) path)
            ;; Build a map from keyword to its position in the path
            route-params (reduce-kv (fn [m i term]
                                      (if (keyword? term)
                                        (assoc m term i)
                                        m))
                                    {}
                                    path)
            ;; Only these two keys are needed by the map traversal dispatcher, so there's no reason to keep
            ;; the full metdata of the endpoint.
            endpoint-meta' (select-keys endpoint-meta [:function :match])]
        (if (= :all method)
          (reduce (fn [dispatch-map method]
                    (add-handler-to-dispatch-map dispatch-map path' method handler endpoint-meta' route-params))
                  dispatch-map
                  supported-methods)
          ;; This is the standard case:
          (add-handler-to-dispatch-map dispatch-map path' method handler endpoint-meta' route-params))))
    {}
    routing-specs))

(defn- endpoint-filter
  [request [_ {endpoint-matcher :match}]]
  (if endpoint-matcher
    (endpoint-matcher request)
    true))

(defn- invoke-leaf'
  [request request-method request-path dispatch-node]
  (let [potentials (get dispatch-node request-method)
        matches (filterv (partial endpoint-filter request) potentials)]
    (case (count matches)
      1 (let [[handler _ route-params] (first matches)]
          (-> request
              ;; convert route-params value from indexes into request-path
              ;; to actual terms from request-path; this is necessary so that
              ;; argument resolver functions can extract the values as endpoint
              ;; function arguments.
              (assoc ::route-params (medley/map-vals (partial nth request-path) route-params)
                     ::path request-path)
              ;; The handler here has the endpoint function at it's "center", but includes
              ;; additional layers to provide arguments, as well as any middleware.
              handler))

      ;; This is ok, it just means that the path is mapped to a method, just not
      ;; the method for this request.
      0 nil

      ;; 2 or more is a problem, since there isn't a way to determine which to invoke.
      (throw (ex-info (format "Request %s matched %d endpoints."
                              (utils/summarize-request request)
                              (count matches))
                      {:request   request
                       :endpoints (map (comp :function second) matches)})))))

(defn- create-map-traversal-handler
  [dispatch-map]
  (fn [request]
    (let [[request-method request-path] (request-route-spec request)]
      (loop [[path-term & remaining-path] request-path
             node dispatch-map]
        (cond-let

          ;; Managed to get to the end of the path?
          (nil? path-term)
          (invoke-leaf' request request-method request-path node)

          [literal-node (get node path-term)]

          (some? literal-node)
          (recur remaining-path literal-node)

          ;; Not an exact match on string, is there a route param placeholder?
          [param-node (get node :_)]

          (some? param-node)
          (recur remaining-path param-node)

          ;; Reach a term in the path that is neither a literal term
          ;; nor a route param placeholder, which results in a non-match.
          )))))

(defn construct-namespace-handler
  "Invoked from [[namespace-handler]] to construct a Ring request handler for the provided
  options and namespace specifications.

  Returns a tuple of the constructed handler and the routing table (the flattened
  version of the namespace specifications)."
  {:added "0.1.20"}
  [options ns-specs]
  (let [routing-table (->> ns-specs
                           (build-namespace-table
                             (get options :context [])
                             (merge-arg-resolver-maps default-arg-resolvers (get options :arg-resolvers))
                             (get options :default-middleware default-namespace-middleware))
                           expand-namespace-metadata
                           (mapcat expand-namespace-entry))
        handler (-> routing-table
                    construct-dispatch-map
                    create-map-traversal-handler)]
    [handler routing-table]))