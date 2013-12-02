# Rook


Rook is a set of middleware and handlers to enable metadata-based routing for Ring web applications.
Internally, it used Compojure and Clout to match routes - and shines best when used in Compojure
application.

## Routing

The difference from standard Compojure-based approach is that instead of linking handlers together through
explicit invocations/mappings, we use namespace scanning middleware, which does just one thing - scans provided namespace and if any of the functions
defined there matches the route spec - either by metadata or by default mappings from function name - sets this functions metadata in request map.

This information set is utilized by rook handler, which then invokes function provided in metadata.

Rook also provides additional functionality of argument resolution helpers - when having a reference to a function,
we can check the names of arguments and autoprovide them - default from request `:params` and `:route-params`, but
it is possible to provide custom resolvers.

### Namespace-aware middleware

So, if we wrap our handlers in `io.aviso.rook/namespace-middleware`:

```
(-> (io.aviso.rook.core/rook-handler)
 (io.aviso.rook.core/namespace-middleware 'some.namespace))
```

and we have functions:

 * `index` (resolved by name to `GET "/"` route spec)
 * and `energize` (with metadata set) defined in `some.namespace`

```
(in-ns 'some.namespace)

(defn index [request]
 {:body "Hello!"})

(defn
 ^{:path-spec [:get "/energize"]}
 energize [request]
 {:body "Hellllooo!"})
```

Any requests going through this middleware and matching a metadata/name of a function from `some.namespace` would have a two
keys in request set:

 * `:rook` with map containing:

   * `:namespace` - reference to functions namespace symbol
   * `:function` - reference to function itself
   * `:metadata` - function metadata
   * `:arg-resolvers` - list of argument resolvers from function metadata

* `:route-params` merged with data returned by `clout/route-matches` - or created if the key is not present in request map

Please note, that when using `namespace-middleware` in conjunction with `compojure.core/context`, the context has to wrap
middleware to make the route path resolution work properly. Example:

```
(compojure.core/context "/merchants" []
  (->
   (io.aviso.rook.core/rook-handler)
   (io.aviso.rook.core/namespace-middleware 'some.namespace)))
```

### Rook-aware handler

The `io.aviso.rook/rook-handler` is a special Ring handler, that checks if a function is referred in `:rook` request map
entry - and then invokes this function using argument resolution mechanism.

## Providing custom argument resolvers

Argument resolver is a function, that takes an argument name and a request map and returns arguments value. A rook-aware
handler will look for `:arg-resolvers`, which set by rook middleware per invoked function and `:default-arg-resolvers`, which
is set using `io.aviso.rook.core/arg-resolver-middleware`.

There are also helper functions to make custom argument resolvers if needed:

* `io.aviso.rook.core/build-map-arg-resolver` - which takes a list of keys and constant values and when required argument
has a corresponding key in the map built from keys and constant values - the value for such key is returned
* `io.aviso.rook.core/build-fn-arg-resolver` - which takes a list of keys and functions and when required argument has
a corresponding key in the built from keys and functions mentioned before - the function is invoked with request as argument

Example:

```
(-> routes
 (io.aviso.rook.core/namespace-middleware 'some.namespace))
 (io.aviso.rook.core/arg-resolver-middleware
  (io.aviso.rook.core/build-map-arg-resolver :key1 "value1" :key2 "value2")
  (io.aviso.rook/core/build-fn-arg-resolver :ip (fn [req] (:remote-addr req)))))
```

You can of course use arg resolver building functions multiple times and provide your own too!