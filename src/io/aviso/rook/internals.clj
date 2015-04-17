(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
  {:no-doc true})

(defn get-injection
  "Retrieves an injected value stored in the request. Throws an exception if the value is falsey."
  [request injection-key]
  {:pre [(some? request)
         (keyword? injection-key)]}
  (or
    (get-in request [:io.aviso.rook/injections injection-key])
    (throw (ex-info (format "Unable to retrieve injected value for key `%s'." injection-key)
                    {:request request}))))

(defn- convert-middleware-form
  [handler-sym metadata-sym form]
  `(or
     ~(if (list? form)
        (list* (first form) handler-sym metadata-sym (rest form))
        (list form handler-sym metadata-sym))
     ~handler-sym))

(defmacro compose-middleware
  "Assembles multiple endpoint middleware forms into a single endpoint middleware. Each middleware form
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
