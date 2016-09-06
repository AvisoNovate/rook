Argument Resolvers
==================

In Rook, endpoint functions may :doc:`have many parameters <endpoints>`, in contrast to a traditional
`Ring <https://github.com/ring-clojure>`_ handler (or middleware), or a Pedestal interceptor.

Argument resolvers are the bridge between the Pedestal context and the individual parameters of
an endpoint function.

Compared to a typical Ring request handler, this saves your code from the work of
destructuring the Ring request map.
Beyond that, it is possible to expose other Pedestal context values, or (via custom
argument resolver generators) entirely other business logic.

From a unit testing perspective, it is likely easier to pass a number of parameters
than it is to construct the necessary Ring response map.

The :arg-resolvers option is a map from keyword to argument resolver `generator`.

When a parameter of an endpoint function has metadata with the matching key, the generator is invoked.

The generator is passed the parameter symbol, and returns the argument resolver function; the
argument resolver function is used during request processing.

An argument resolver function is passed the Pedestal context and returns the argument value.
There will be an individual argument resolver function created for each endpoint function parameter.

By convention, when the metadata value is the literal value ``true``, the symbol is converted to a keyword.

So, if an endpoint function has a parameter ``^:request body``, the :request argument resolver generator
will return an argument resolver function equivalent to:

.. code-block:: clojure

    (fn [context]
      (let [v (get-in context [:request :body])]
        (if (some? v)
          v
          (throw (ex-info ...)))))

Predefined Argument Resolvers
-----------------------------

:request

    Key in the Ring request map (:request). Value may not be nil.

:path-param

    A path parameter, value may not be nil.

:query-param

    A query parameter, as an HTTP decoded string.

:form-param

    A form parameter.  Endpoints using this should include the
    io.aviso.rook.interceptors/keywordized-form interceptor.

Further details about these are in the API documentation.

Defining New Argument Resolvers
-------------------------------

It is completely reasonable to provide your own :arg-resolvers (as the :arg-resolvers key in the options
passed to io.aviso.rook/gen-table-routes).

.. code-block:: clojure

    (require '[io.aviso.rook :as rook])

    (defn inject-arg-resolver [injections]
      (fn [sym]
        (let [k (rook/meta-value-for-parameter sym :inject)
              injection (get injections k)]
            (fn [_] injection))))

    ...

        (rook/gen-table-routes {...}
                               {:arg-resolvers {:inject (inject-arg-resolver dependencies)}})


In the above example code, when the table routes are created, the dependencies symbol contains values
that an endpoint function might need; for example, a database connection or the like.

When :inject is seen on a parameter symbol, the symbol is converted to a keyword via the
function meta-value-for-parameter.
This is then used to find the specific dependency value; finally, an argument resolver is returned that ignores the
provided context and supplies the value.

Argument Resolver Errors
------------------------

Each endpoint parameter must apply to one and only one argument resolver.  If you apply more than one
(say ``^:inject ^::request body``), you'll see an exception thrown from gen-table-routes.



