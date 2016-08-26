Argument Resolvers
==================

In Rook, endpoint functions may :doc:`have many parameters <endpoints>`, in contrast to a traditional
`Ring <https://github.com/ring-clojure>`_ handler (or middleware), or a Pedestal interceptor.

Argument resolvers are the bridge between the Pedestal context and the individual parameters of
an endpoint function.

The :arg-resolvers option is a map from keyword to argument resolver `generator`.

When a parameter of an endpoint function has metadata with the matching key, the generator is invoked.

The generator is passed the parameter symbol, and returns the arg resolver function; this function
is passed the Pedestal context and returns the argument value.

By convention, when the metadata value is `true`, the symbol is converted to a keyword.

So, if an endpoint function has a parameter ``^:request body``, the :request argument resolver
will return a function equivalent to:

.. code-block:: clojure

    (fn [context]
      (let [v (get-in context [:request :body])]
        (if (some? v)
          v
          (throw (ex-info ...)))))



