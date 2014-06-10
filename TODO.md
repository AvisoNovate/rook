## io.aviso.rook.dispatcher

### middleware changes

Currently, middleware used in the context of each request handler function is traditional style:
it is passed a handler (async or normal) and returns an intercepting handler. 
Often the intercepting handler will analyze the meta data provided in the request (under `[:rook :metadata]`)
to determine whether to apply logic, or simply delegate to the original handler.

Since we are targeting the creation of a custom call site for each request handler function, it follows
that there will be a pipeline specific to each request handler function.
This is an opportunity for greater efficiency, as the middleware can, if provided with more meta-data,
make a _static_ decision about applying an intercepting handler.

This changes the contract for middleware; instead of being passed just the handler, the middleware will
be passed the handler and the metadata for the function. 
The middleware will return the handler, or an interceptor around the handler.

### Revised argument resolution

As currently implemented, argument resolution is arbitrarily expensive. At the call site, a value for
each function argument is determined _on each invocation_.
Argument resolution requires a search among an set of argument resolvers, looking for a non-nil result.
This is necessary because, for an arbitrary function argument, there is currently no way to know from where
the value will be resolved; based on the exact set of argument resolvers available at the call site,
the value may be anything from the request itself, to a key stored in the request, to a value extracted from
the `:params` map or the `:headers` map, to some arbitrary value entirely.

The proposed change is that argument resolution take place statically. 

The current contract for argument resolvers is:

   (argument-keyword request) -> value (or nil)
   
The new contract will be:

   (argument-symbol) -> (request) -> value  (or nil)
   
Argument resolvers will be able to access meta-data associated with the symbol via the `meta` function; various
tag values will indicate that the symbol in question is to be sourced from the `:params` or `:headers`, for example.

Other argument resolvers can operate by matching the name specifically.

The return value of an argument resolver is a function that is passed the request and returns the actual value.

### Middleware Lists

Currently middleware for any individual path spec is provided as a single function; it is assumed that
where there is multiple middleware, they can be combined using `->`.

Instead, middleware will be a list of such functions, applied in the provided order.

### Dispatch Sugar

The current signature for creating a dispatch table is fine for when there's only single namespace; we need
some form of sugar (function or macro) to make it effective and readable when there's a large number of namespaces,
some nested under other namespaces.