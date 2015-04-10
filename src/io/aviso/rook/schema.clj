(ns io.aviso.rook.schema
  "Some small enhancements to Prismatic Schema that are valuable to, or needed by, the Swagger support."
  {:added "0.1.27"})

(defmacro schema
  "Creates a named schema, which includes metadata as per [[defschema]]. This is useful for one-off
  schemas, such as those used in responses."
  ([name form]
   `(schema ~name "" ~form))
  ([name docstring form]
   `(-> ~form
        (vary-meta merge ~(-> &form second meta))
        ;; The second form is the name (the first is the 'schema symbol itself) and we want its metadata
        (vary-meta assoc :name '~name :ns *ns* :doc ~docstring))))

(defmacro defschema
  "Convenience macro to make it clear to reader that body is meant to be used as a schema, and to provide
  extra data needed for generating Swagger descriptions.

  This extends schema.core/defschema to merge the symbol's metadata with keys :name (the unqualified
  symbol being defined), :ns (the namespace), :doc (the docstring) plus any custom metdata
   from the name.  Normally, much of that data is only available to the Var created by the underlying def."
  ([name form]
   `(defschema ~name "" ~form))
  ([name docstring form]
   `(def ~name ~docstring (schema ~name ~docstring ~form))))


