(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require
    [io.aviso.rook
     [dispatcher :as dispatcher]
     [internals :as internals]
     [utils :as utils]]
    [ring.middleware params format keyword-params]
    [clojure.string :as str]
    [medley.core :as medley]))

(defn inject*
  "Merges the provided map of injectable argument values into the request. Keys should be keywords
  (that will match against function argument symbols, converted to keywords)."
  {:added "0.1.10"}
  [request injection-map]
  (medley/update request :io.aviso.rook/injections merge injection-map))

(defn inject
  "Merges a single symbol key and value into the map of injectable argument values."
  {:added "0.1.10"}
  [request key value]
  {:pre [(keyword? key)
         (some? value)]}
  (inject* request {key value}))

(defn wrap-with-injection
  "Wraps a request handler with an injection of the key and value."
  {:added "0.1.10"}
  [handler key value]
  (fn [request]
    (-> request
        (inject key value)
        handler)))

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
  (internals/wrap-with-arg-resolvers handler arg-resolvers))

(defn resource-uri-arg-resolver
  "Calculates the URI for a resource (handy when creating a Location header, for example).
  First, calculates the server URI, which is either the :server-uri key in the request _or_
  is calculated from the request's :scheme, :server-name, and :server-port.  It should be
  the address of the root of the server.

  From there, the :context key (made available by the dispatch
  mechanism) is used to assemble the rest of the URI.

  The URI ends with a slash."
  [request]
  (internals/resource-uri-for request))

(defn clojureized-params-arg-resolver
  "Converts the Request :params map, changing each key from embedded underscores to embedded dashes, the
  latter being more idiomatic Clojure.

  For example, you could define a parameter as:

      {:keys [user-id email new-password] :as params*}

  Which will work, even if the actual keys in the :params map were :user_id, :email, and :new_password.

  This reflects the default configuration, where `params*` is mapped to this function."
  [request]
  (internals/clojurized-params-arg-resolver request))

(defn wrap-with-standard-middleware
  "The standard middleware that Rook expects to be present before it is passed the Ring request."
  [handler]
  (-> handler
      (ring.middleware.format/wrap-restful-format :formats [:json-kw :edn])
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(defn namespace-handler
  "Examines the given namespaces and produces either a Ring handler or
  an asynchronous Ring handler (for use with functions exported by the
  io.aviso.rook.async and io.aviso.rook.jetty-async-adapter
  namespaces).

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

  :async?

  : _Default: false_

  : If `true`, an async handler will be returned.

  Example call:

      (namespace-handler
        {:context-pathvec    [\"api\"]
         :default-middleware basic-middleware}
        ;; foo & bar use basic middleware:
        [[\"foo\"]  'example.foo]
        [[\"bar\"]  'example.bar]
        ;; quux has special requirements:
        [[\"quux\"] 'example.quux special-middleware])."
  [options? & ns-specs]
  (dispatcher/compile-dispatch-table (if (map? options?) options?)
    (apply dispatcher/namespace-dispatch-table options? ns-specs)))
