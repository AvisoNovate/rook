(ns io.aviso.rook
  "Rook is a simple package used to map the functions of a namespace as web resources, following a naming pattern or explicit meta-data."
  (:require
    [io.aviso.rook [dispatcher :as dispatcher]]
    [io.aviso.toolchest.macros :refer [consume]]
    [io.aviso.toolchest.collections :refer [pretty-print]]
    [ring.middleware params format keyword-params]
    [medley.core :as medley]
    [potemkin :as p]
    [io.aviso.tracker :as t]
    [clojure.tools.logging :as l]))

(p/import-vars

  [io.aviso.rook.internals get-injection])

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
  (medley/update request ::injections merge injection-map))

(defn inject
  "Merges a single keyword key and value into the map of injectable argument values store in the request.
  This is associated with the :injection argument metadata. The key must be a symbol; the actual argument
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

(defmacro try-swagger [& body]
  `(try
    (require 'io.aviso.rook.swagger)
    ~@body
    (catch ClassNotFoundException e#
      (l/warn ":swagger was specified as an option but is not available - check dependencies to ensure ring-swagger is included"))))

(defn namespace-handler
  "Examines the given namespaces and produces either a Ring handler or
  an asynchronous Ring handler (for use with functions exported by the
  io.aviso.rook.async and io.aviso.rook.jetty-async-adapter
  namespaces).

  Options can be provided as a leading map (which is optional).

  The individual namespaces are specified as vectors of the following
  shape:

      [context? ns-sym middleware?]

  The optional fragments are interpreted as below (defaults listed in
  brackets):

   - context? ([]):

     A context (a string, or a vector of strings and/or keywords) to be prepended to paths for all entries
     emitted for this namespace.

   - ns-sym

     The symbol for the namespace that is to be mapped.

   - middleware? ([[dispatcher/default-namespace-middleware]] or as supplied in options):

     Middleware to be applied to terminal handlers found in this
     namespace.

  The options map, if supplied, can include a number of values
  as defined by [[dispatcher/compile-dispatch-table]] and
  [[dispatcher/namespace-dispatch-table]].

  Example call:

      (namespace-handler
        {:context            [\"api\"]
         :default-middleware basic-middleware}
        ;; foo & bar use basic middleware:
        [\"foo\" 'example.foo]
        [\"bar\" 'example.bar]
        ;; quux has special requirements:
        [[\"quux\"] 'example.quux special-middleware])."
  {:arglists '([options & ns-specs]
               [& ns-specs])}
  [& &ns-specs]
  (t/track
    #(format "Building namespace handler for %s." (pretty-print &ns-specs))
    (consume &ns-specs
      [options map? :?
       ns-specs :&]
      (let [swagger-enabled (:swagger options)
            options' (if swagger-enabled
                       (try-swagger
                         (assoc-in options [:arg-resolvers 'swagger]
                                   (constantly
                                     ((resolve 'io.aviso.rook.swagger/namespace-swagger) options ns-specs))))
                       (or options {}))
            ns-specs' (if swagger-enabled
                        (try-swagger
                          ((resolve 'io.aviso.rook.swagger/swaggerize-ns-specs) ns-specs))
                        ns-specs)
            dispatch-table (apply dispatcher/namespace-dispatch-table options' ns-specs')]
        (dispatcher/compile-dispatch-table options' dispatch-table)))))


(defn- convert-middleware-form
  [handler-sym metadata-sym form]
  `(or
     ~(if (list? form)
        (list* (first form) handler-sym metadata-sym (rest form))
        (list form handler-sym metadata-sym))
     ~handler-sym))

(defmacro compose-middleware
  "Assembles multiple namespace middleware forms into a single namespace middleware. Each middleware form
  is either a list or a single form, that will be wrapped as a list.

  The list is modified so that the first two values passed in are the previous handler and the metadata (associated
  with the endpoint function).

  The form should evaluate to a new handler, or the old handler. As a convienience, the form may
  evaluate to nil, which will keep the original handler passed in.

  Returns a function that accepts a handler and middleware and invokes each middleware form in turn, returning
  a final handler function.

  This is patterned on Clojure's -> threading macro, with some significant differences."
  [& middlewares]
  (let [handler-sym (gensym "handler")
        metadata-sym (gensym "metadata")]
    `(fn [~handler-sym ~metadata-sym]
       (let [~@(interleave (repeat handler-sym)
                           (map (partial convert-middleware-form handler-sym metadata-sym) middlewares))]
         ~handler-sym))))
