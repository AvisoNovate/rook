(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require
    [io.aviso.rook.internals :as internals]
    [clojure.tools.logging :as l]
    [clojure.string :as str]
    [compojure.core :as compojure]
    [clout.core :as clout]))

(defn build-map-arg-resolver
  "Build an argument resolver which takes a list of keys and constant values and when required argument
has a corresponding key in the map built from keys and constant values - the value for such key is returned."
  [& kvs]
  (let [arg-map (apply hash-map kvs)]
    (fn [arg request]
      (get arg-map arg))))

(defn build-fn-arg-resolver
  "Build an argument resolver which takes a list of keys and functions and when required argument has
a corresponding key in the built from keys and functions mentioned before - the function is invoked with request as argument. "
  [& kvs]
  (let [arg-map (apply hash-map kvs)]
    (fn [arg request]
      (when-let [fun (get arg-map arg)]
        (fun request)))))

(defn request-arg-resolver
  "Standard argument resolver that adds values from request to map to resolved arguments."
  [arg request]
  (get request arg))

(defn arg-resolver-middleware
  "Middleware which adds provided argument resolvers to the [:rook :arg-resolvers] collection."
  [handler & arg-resolvers]
  (fn [request]
    (handler (update-in request [:rook :arg-resolvers] concat arg-resolvers))))

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
              (l/debugf "Mapping %s `%s' to %s" (-> route-method name .toUpperCase) route-path f)
              [route-method (clout/route-compile route-path) f full-meta]))
       (remove nil?)
       doall))

(defn namespace-middleware
  "Middleware that scans provided namespace and if any of the functions defined there matches the route spec -
  either by metadata or by default mappings from function name - sets this functions metadata in request map.

  This does not invoke the function; that is the responsibility of the rook-dispatcher function. Several additional
  middleware filters will typically sit between identifying the function and actually invoking it."
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
  "Ring request handler that uses information from :rook entry in the request map to invoke the previously
  identified function, after resolving the function's arguments. This function must always be wrapped
  in namespace-middleware (which is what identifies the resource handler function to invoke).

  This should always be wrapped with wrap-with-function-arg-resolvers, to ensure that function-specific
  argument resolvers are present in the [:rook :arg-resolvers] key."
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
    (handler (update-in request [:rook :arg-resolvers] concat (-> request :rook :metadata :arg-resolvers)))))

(def default-rook-pipeline
  "The default pipeline for invoking a resource handler function: wraps rook-dispatcher
  to do the actual work with middleware to extend :args-resolvers with function-specific arg resolvers."
  (-> rook-dispatcher wrap-with-function-arg-resolvers))

(defn namespace-handler
  "Helper handler, which wraps rook-dispatcher in namespace middleware.

  path - if not nil, then a Compojure context is created to contain the created middleware and handler.
  The path should start with a slash, but not end with one.
  namespace-name - the symbol identifying the namespace to scan, e.g., 'org.example.resources.users
  handler - The handler to use; defaults to rook-dispatcher, but in many cases, you will want to wrap
  default-rook-pipeline with additional middleware, or combine several handlers into a single Compojure route.

  The advanced version also takes a path for compojure.core/context and the handler to invoke."
  ;; I'm thinking that handlers is wrong; the handlers should actually be middleware.
  ([namespace-name]
   (namespace-handler nil namespace-name))
  ([path namespace-name]
   (namespace-handler path namespace-name default-rook-pipeline))
  ([path namespace-name handler]
   (let [handler' (namespace-middleware handler namespace-name)]
     (if path
       (compojure/context path [] handler')
       handler'))))


