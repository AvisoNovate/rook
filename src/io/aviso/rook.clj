(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require
    [medley.core :as medley]
    [io.aviso.rook
     [schema-validation :as v]
     [internals :as internals]
     [utils :as utils]]
    [ring.middleware params format keyword-params]
    [clojure.tools.logging :as l]
    [clojure.string :as str]
    [compojure.core :as compojure]
    [clout.core :as clout]))

(defn- prefix-with
  "Like concat, but with arguments reversed."
  [coll1 coll2]
  (concat coll2 coll1))

(defn build-map-arg-resolver
  "Builds a static argument resolver around the map of keys and values; the values are the exact resolved
  value for arguments matching the keys."
  [arg-map]
  (fn [arg request]
    (get arg-map arg)))

(defn build-fn-arg-resolver
  "Builds dynamic argument resolvers that extract data from the request. The value for each key is a function;
  the function is invoked to resolve the argument matching the key. The function is passed the Ring request map."
  [fn-map]
  (fn [arg request]
    (when-let [f (get fn-map arg)]
      (f request))))

(defn request-arg-resolver
  "A dynamic argument resolver that simply resolves the argument to the matching key in the request map."
  [arg request]
  (get request arg))


(defn wrap-with-arg-resolvers
  "Middleware which adds the provided argument resolvers to the `[:rook :arg-resolvers]` collection.
  Argument resolvers are used to gain access to information in the request, or information that
  can be computed from the request, or static information that can be injected into resource handler
  functions."
  [handler & arg-resolvers]
  (fn [request]
    (handler (update-in request [:rook :arg-resolvers] prefix-with arg-resolvers))))

(defn- require-port?
  [scheme port]
  (case scheme
    :http (not (= port 80))
    :https (not (= port 443))
    true))

(defn resource-uri-arg-resolver
  "Calculates the URI for a resource (handy when creating a Location header, for example).
  First, calculates the server URI, which is either the :server-uri key in the request _or_
  is calculated from the request's :scheme, :server-name, and :server-port.  It should be
  the address of the root of the server.

  From there, the :context key (maintained by Compojure, when delving into nested contexts)
  is used to assemble the rest of the URI.

  The URI ends with a slash."
  [request]
  (let [server-uri (or (:server-uri request)
                       (str (-> request :scheme name)
                            "://"
                            (-> request :server-name)
                            (let [port (-> request :server-port)]
                              (if (require-port? (:scheme request) port)
                                (str ":" port)))))]
    (str server-uri (:context request) "/")))



(defn clojureized-params-arg-resolver
  "Converts the Request :params map, changing each key from embedded underscores to embedded dashes, the
  latter being more idiomatic Clojure.

  For example, you could define a parameter as:

      {:keys [user-id email new-password] :as params*}

  Which will work, even if the actual keys in the :params map were :user_id, :email, and :new_password.

  This reflects the default configuration, where `params*` is mapped to this function."
  [request]
  (->> request
       :params
       (medley/map-keys internals/to-clojureized-keyword)))

(defn wrap-with-default-arg-resolvers
  "Adds a default set of argument resolvers, allowing for resolution of:

   - :request - the Ring request map
   - :params  - the :params key of the Ring request map
   - :params*  - the :params key of the Ring request map, with keys _Clojurized_
   - :resource-uri - via [[resource-uri-arg-resolver]]
   - the argument as a parameter from the :params map
   - the argument as a parameter from the :route-params map
   - the argument as a header from the :headers map"
  [handler]
  (wrap-with-arg-resolvers handler
                           (fn [kw request]
                             (or
                               (get-in request [:params kw])
                               (get-in request [:route-params kw])
                               (get-in request [:headers (name kw)])))
                           (build-fn-arg-resolver {:request      identity
                                                   :resource-uri resource-uri-arg-resolver
                                                   :params       :params
                                                   :params*      clojureized-params-arg-resolver})))

(defn- get-compiled-paths
  [namespace-name]
  (l/debugf "Scanning %s for mappable functions" namespace-name)
  (->> (internals/get-available-paths namespace-name)
       (map (fn [[route-method route-path f full-meta]]
              (assert (internals/supported-methods route-method)
                      (format "Method %s (from :path-spec of function %s) must be one of %s."
                              route-method
                              f
                              (str/join ", " internals/supported-methods)))
              ;; It would be nice if there was a way to qualify the path for when we are nested
              ;; inside a Compojure context, but we'd need the request to do that.
              (l/debugf "Mapping %s to %s"
                        (utils/summarize-method-and-uri route-method route-path) f)
              [route-method (clout/route-compile route-path) f full-meta]))
       (remove nil?)
       doall))

(defn wrap-namespace
  "Middleware that scans provided namespace and if any of the functions defined there matches the route spec -
  either by metadata or by default mappings from function name - sets this functions metadata in request map.

  This does not invoke the function; that is the responsibility of the [[rook-dispatcher]]
  function. Several additional middleware filters will typically sit between identifying the function and actually invoking it."
  [handler namespace-name]
  (let [compiled-paths (get-compiled-paths namespace-name)]
    (fn [request]
      ;; The handler is always invoked.  If there's a matching function in the namespace,
      ;; then the request will contain that matching function data in the :rook key. Otherwise
      ;; the handler is invoked with the unchanged request (because a nested context may
      ;; want to handle the request).
      (handler (or
                 (internals/match-against-compiled-paths request namespace-name compiled-paths)
                 request)))))

(defn rook-dispatcher
  "Ring request handler that uses information from :rook map (within the Ring request map) to invoke the previously
  identified function, after resolving the function's arguments. This function must always be wrapped
  in [[wrap-namespace]] (which is what identifies the resource handler function that is to be invoked).

  This should also always be wrapped with [[wrap-with-function-arg-resolvers]],
  to ensure that function-specific argument resolvers are present in the `[:rook :arg-resolvers]` key."
  [{{f :function metadata :metadata resolvers :arg-resolvers} :rook :as request}]
  (let [args (-> metadata :arglists first)
        argument-values (map #(internals/extract-argument-value % request resolvers) args)]
    (when f
      (l/debug "Invoking handler function" f)
      (apply f argument-values))))

(defn wrap-with-function-arg-resolvers
  "Wraps the handler with a request that has the :arg-resolvers key extended with any
  function-specific arg-resolvers (from the function's meta-data)."
  [handler]
  (fn [request]
    (handler (update-in request [:rook :arg-resolvers] prefix-with (-> request :rook :metadata :arg-resolvers)))))

(def default-rook-pipeline
  "The default pipeline for invoking a resource handler function: wraps
  [[rook-dispatcher]] to do the actual work with middleware to extend :args-resolvers with function-specific arg resolvers and
  schema validation."
  (-> rook-dispatcher
      wrap-with-function-arg-resolvers
      v/wrap-with-schema-validation))

(defn namespace-handler
  "Helper handler, which wraps rook-dispatcher in namespace middleware.

  - path - if not nil, then a Compojure context is created to contain the created middleware and handler.
    The path should start with a slash, but not end with one.
  - namespace-name - the symbol identifying the namespace to scan, e.g., `'org.example.resources.users`
  - handler - The handler to use; defaults to [[default-rook-pipeline]], but in many cases, you will want to wrap
    or replace `default-rook-pipeline` with additional middleware, or combine several handlers into a single Compojure route.

  The advanced version also takes a path for `compojure.core/context` and the handler to invoke."
  ;; I'm thinking that handlers is wrong; the handlers should actually be middleware.
  ([namespace-name]
   (namespace-handler nil namespace-name))
  ([path namespace-name]
   (namespace-handler path namespace-name default-rook-pipeline))
  ([path namespace-name handler]
   (let [handler' (wrap-namespace handler namespace-name)]
     (if path
       (compojure/context path [] handler')
       handler'))))

(defn wrap-with-standard-middleware
  "The standard middleware that Rook expects to be present before it is passed the Ring request."
  [handler]
  (-> handler
      wrap-with-default-arg-resolvers
      (ring.middleware.format/wrap-restful-format :formats [:json-kw :edn])
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

