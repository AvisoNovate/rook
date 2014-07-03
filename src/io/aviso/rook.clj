(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require
    [io.aviso.rook
     [dispatcher :as dispatcher]
     [internals :as internals]]
    [ring.middleware params format keyword-params]
    [medley.core :as medley]))

(defn get-injection
  "Retrieves an injected value stored in the request. Throws an exception if the value is falsey."
  {:added "0.1.11"}
  [request injection-key]
  (internals/get-injection request injection-key))

(defn inject*
  "Merges the provided map of injectable argument values into the request. Keys should be keywords
  (that will match against function argument symbols, converted to keywords)."
  {:added "0.1.10"}
  [request injection-map]
  (medley/update request ::injections merge injection-map))

(defn inject
  "Merges a single keyword key and value into the map of injectable argument values. This is
  associated with the :injection argument meta data. The key must be a symbol; the actual argument
  will be converted to a keyword for lookup."
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

(defn wrap-with-injections
  "Wraps a request handler with injections by merging in a map."
  {:added "0.1.11"}
  [handler injections]
  (fn [request]
    (-> request
        (inject* injections)
        handler)))

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

  :arg-symbol->resolver
  : _Default: [[io.aviso.rook.dispatcher/default-arg-symbol->resolver]]_
  : Map from symbol to a function of request that resolves an argument.
    This is used to support the cases where an argument's name defines
    how it is resolved.

  :resolver-factories
  : _Default: [[io.aviso.rook.dispatcher/default-resolver-factories]]_
  : Used to specify a map from keyword to argument resolver function _factory_. The keyword is a marker
    for meta data on the symbol (default markers include :header, :param, :injection, etc.). The value
    is a function that accepts a symbol and returns an argument resolver function (that accepts the Ring
    request and returns the argument's value).

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
