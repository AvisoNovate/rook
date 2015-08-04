(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require [io.aviso.rook
             [dispatcher :as dispatcher]
             [swagger :as swagger]]
            [io.aviso.toolchest.macros :refer [consume]]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [ring.middleware params format keyword-params]
            [medley.core :as medley]
            [potemkin :as p]
            [io.aviso.tracker :as t]))

(p/import-vars
  [io.aviso.rook.internals get-injection compose-middleware])

(defn find-injection
  "Retrieves an optional injected value from the request, returning nil if the value does not exist."
  {:added "0.1.11"}
  [request injection-key]
  (get-in request [::injections injection-key]))

(defn inject*
  "Merges the provided map of injectable argument values into the request. Keys should be keywords
  (that will match against function argument symbols, converted to keywords)."
  {:added "0.1.10"}
  [request injection-map]
  (update-in request [::injections] merge injection-map))

(defn inject
  "Merges a single keyword key and value into the map of injectable argument values store in the request.
  This is associated with the :injection argument metadata."
  {:added "0.1.10"}
  [request key value]
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
  an asynchronous Ring handler.

  Options can be provided as a leading map (which is optional).

  The individual namespaces are specified as vectors of the following
  shape:

      [context? ns-sym argument-resolvers? middleware? nested...?]

  The optional fragments are interpreted as below:

  context?
  : A context (a string, or a vector of strings and/or keywords) to be prepended to paths for all entries
  emitted for this namespace.

  ns-sym
  : The symbol for the namespace that is to be mapped.
  : This is the only required value in the namespace specification.

  argument-resolvers?
  : A map of argument resolvers that apply to the namespace, and to any child namespaces.

  middleware?
  : Middleware to be applied to terminal handlers found in this
  namespace.

  nested...?
  : Defines child namespaces, each its own recursive namespace specification.
  Child namespaces inherit the context, argument resolvers, and middleware
  of the containing namespace.

  Supported options and their default values:

  :arg-resolvers
  : Map of symbol to (keyword or function of request) or keyword
    to (function of symbol returning function of request). Entries of
    the former provide argument resolvers to be used when resolving
    arguments named by the given symbol; in the keyword case, a known
    resolver factory will be used.
  : Normally, the provided map is merged into the map inherited from
    the containing namespace (or elsewhere), but this can be controlled
    using metadata on the map.
  : Tag with {:replace true} to
    exclude inherited resolvers and resolver factories; tag with
    {:replace-resolvers true} or {:replace-factories true} to leave
    out default resolvers or resolver factories, respectively.

  :context
  : _Default: []_
  : A root context that all namespaces are placed under, for example [\"api\"].

  :default-middleware
  : _Default: [[default-namespace-middleware]]_
  : Default endpoint middleware applied to the basic handler for
    each endpoint function (the basic handler resolver arguments and passes
    them to the endpoint function).
    The default leaves the basic handler unchanged.

  :swagger-options
  : Options to be passed to [[construct-swagger-object]], that control how the Swagger description is composed and
    customized.
    Swagger support is only enabled when :swagger-options is non-nil.

  Example call:

       (namespace-handler
         {:context            [\"api\"]
          :swagger-options    swagger/default-swagger-options
          :default-middleware custom-middleware
          :arg-resolvers      {'if-unmodified-since :header
                               'if-modified-since   :header}}
         ;; foo & bar use custom-middleware:
         [\"hotels\" 'org.example.resources.hotels
           [[:hotel-id \"rooms\"] 'org.example.resources.rooms]]
         [\"bars\" 'org.example.resources.bars]
         ;; taxis has special requirements:
         [\"taxis\" 'org.example.resources.taxis
           {:dispatcher dispatcher-resolver-factory}
           taxi-middleware])."
  {:arglists '([options & ns-specs]
                [& ns-specs])}
  [& &ns-specs]
  (t/track
    "Constructing Rook namespace handler."
    (consume &ns-specs
             [{:keys [swagger-options] :as options} map? :?
              ns-specs :&]
             (let [swagger-enabled        (some? swagger-options)
                   swagger-object-promise (promise)
                   ;; The challenge here is to isolate this as much as possible from the rest of the
                   ;; API and whatever kinds of middleware is in play.
                   swagger-spec           [(:path swagger-options) 'io.aviso.rook.resources.swagger
                                           {'swagger-object (fn [_] @swagger-object-promise)}
                                           dispatcher/default-namespace-middleware]
                   ns-specs'              (if swagger-enabled
                                            (cons swagger-spec ns-specs)
                                            ns-specs)
                   [handler routing-table] (dispatcher/construct-namespace-handler options ns-specs')]
               (if swagger-enabled
                 (deliver swagger-object-promise (swagger/construct-swagger-object swagger-options routing-table)))
               handler))))


(defn resolve-argument-value
  "Resolves an argument, as if it were an argument to an endpoint function.

  request
  : The Ring request map, as passed through middleware and to the endpoint.

  argument-meta
  : A map of metadata about the symbol, or a single keyword. A keyword is converted
    to a map (of the keyword, to true).

  argument-symbol
  : A symbol identifying the name of the parameter. This is sometimes needed to construct
    the argument resolver function.

  Throws an exception if no (single) argument resolver can be identified."
  {:added "0.1.35"}
  [request argument-meta argument-symbol]
  (dispatcher/resolve-argument-value request
                               (if (keyword? argument-meta)
                                 {argument-meta true}
                                 argument-meta)
                               argument-symbol))