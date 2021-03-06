## 0.2.2 - 2 Dec 2016

Refactored the argument resolvers out to their own namespace.

Added an injection argument provider

## 0.2.1 - 2 Sep 2016

Updated dependencies to Pedestal 0.5.1.

Added support for :route-name metadata.

Improvements to documentation.

## 0.2.0  - 26 Aug 2016

A major pivot to change Rook to be a way of generating Pedestal routing tables.

## 0.1.37 - UNRELEASED

Improvements to the initial example provided in the manual.

Updated a number of dependencies to latest.

`lein with-profile +1.6 spec` now runs tests under 1.6 and `lein spec` under 1.7

Custom endpoint coercions for request parameters.

## 0.1.36 - 17 Aug 2015

Relaxed restrictions on :format with io.aviso.rook.schema/with-data-type.

Added significant amounts of Prismatic Schema to the structures and functions in
io.aviso.rook.swagger.

## 0.1.35 - 4 Aug 2015

Added io.aviso.rook/resolve-argument, which is used to access argument values using the same resolution logic
as when invoking endpoint functions.

## 0.1.34 - 31 Jul 2015

Rolled back Clojure compatibility to 1.6.

## 0.1.33 - 15 Jul 2015

More Swagger fixes:

- Change "otherProperties" to "additionalProperties"
- Include `type: 'object'` for object schemas
- Dig down through nested schemas to find the data type mapping (much like description extraction in 0.1.32)
- New function io.aviso.rook.schema/with-data-type is used to annotate the type and format for a schema where this
  can't be normally deduced, such as when using schema.core/pred.
- New function io.aviso.rook.schema/with-usage-description allows a description of how a schema is being used, distinct
  from the schemas own description.
- Metadata on endpoint function parameters is now evaluated, as was already done with namespace metadata
- Schemas created via schema.core/maybe now include `"x-nullable": true` in their definition.

## 0.1.32 - 14 Jul 2015

Small improvements to how documentation is extracted from nested schemas (as part of the Swagger support).
For example,
`(s/maybe (rs/with-description "The frobinator's unique id." s/Str))` will now find that
description, where previously it did not.

Update to latest dependencies, including Ring 1.4.0.

## 0.1.31 - 7 Jul 2015

Updated Clojure dependency to 1.7.0.

Rook can now handle coercion, even when the schema has been decorated with a description via
io.aviso.rook.schema/with-description.

Description strings extracted from :doc or :documentation metadata are now re-indented before being emitted
into the Swagger API description. Previously, lines after the first were indented (typically) two extra characters
and some Markdown extensions would have trouble parsing the result properly.

## 0.1.30 - 15 Jun 2015

Adds support for :* in an endpoint's route metadata. :* matches one or more path terms; the matched portion of the URI
is available as the endpoint argument `wildcard-path`.

## 0.1.29 - 2 Jun 2015

Improvements to the generation of Swagger API descriptions:

* Descriptions of properties of schemas are now captured
* :description meta data on path variables is now captured
* Header arguments are now identified and documented

## 0.1.28 - 29 Apr 2015

Added the :lazy option to io.aviso.rook.server/construct-handler.

Added support for schema.core/enum keys when generating Swagger API documentation.

## 0.1.27 - 17 Apr 2015

It is now possible, and encouraged, to apply schema validation to the :query-params, :form-params, and/or :body-params
individually. Previously only :params could be validated and coerced.

When the necessary metadata (:query-schema, :form-schema, :body-schema, :schema) is present, then:

* The corresponding request key is extracted (:query-params, :form-params, etc.)
* String keys are converted to keywords, recursively (applies to :form-params and :query-params)
* Prismatic Schema is used to coerce and validate
* The result is stored back into the request under the same key
* The :params key is rebuilt from the merge of the :query-params, :form-params, and :body-params keys

Swagger 2.0 support has been completely rewritten. *In progress.* 

The io.aviso.rook.client namespace has been removed with no replacement.

## 0.1.26 - 30 Mar 2015

Rook is now focused on dispatch of incoming requests to endpoints; a hard look at the async features have found them to
be problematic on several fronts, and not providing any measurable performance improvement; they have been removed.
 
io.aviso.rook.server has a new function, wrap-with-exception-catching, which catches exceptions, reports them
(using io.aviso/tracker), and returns a 500 failure response.

The construct-handler function has been enhanced with new options to enable exception catching, and to provide
the standard Rook middleware. 

In addition, there has been a general refresh of dependencies to latest.

## 0.1.25 - 12 Mar 2015

When there is an exception creating the handler, io.aviso.rook.server now replaces the handler with one that returns
a 500 response that includes the error message from the exception.
Previously, a handler that handler creator that threw an exception would prevent the server from starting up (in production mode), or
cause a Jetty exception on each request (in development mode).

## 0.1.24 - 3 Feb 2015

Straightened out some conflicts and missing dependencies.

Big improvements to the documentation, especially the description of mapping namespaces.

New functions:

* io.aviso.rook.server/wrap-track-request
* io.aviso.rook.async/timed-out?

## 0.1.23 - 15 Jan 2015

* In the options map passed to server/construct-handler, :log is no longer implied by :debug,
  they are entirely separate now.
* Failure response bodies are now more uniform, and generated by new function io.aviso.rook.utils/failure-response.
* When using wrap-with-timeout, there is now a :timeout-control-ch key added to the request, allowing
  the timeout to be triggered early or canceled entirely.

## 0.1.22 - 9 Jan 2015

* Beefed up the logging of requests and responses inside io.aviso.rook.server. 

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aclosed+milestone%3A0.1.22+)

## 0.1.21 - 8 Jan 2015

* Removed the io.aviso.rook.jetty-async-adapter namespace; use [Jet](https://github.com/mpenet/jet) instead.
* The dependency on Ring was switched from ring to ring/ring-core; you may need to add ring/ring-jetty-adapter 
  in to your project.

## 0.1.20 - 23 Dec 2014

* Rewritten io.aviso.rook.dispatcher; simplified, and adds the ability to define
  argument resolvers for each namespace.
* Consolidated the documentation for namespace specifications and options in a single place.
* Attempting to use Swagger without the necessary dependencies will now be a failure (a
  descriptive exception will be thrown).
* Lots of other progress on making the Swagger support usable.

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aclosed+milestone%3A0.1.20+)

## 0.1.19 - 11 Dec 2014

* Improvements to response validation, especially when exceptions occur during validation
* io.aviso.rook.client has changed in minor, but incompatible ways (see below)
* Removed the quotes from around the request URI when logging the request method and URI
* Added a sample implementation of a io.aviso.rook.client request handler, based on clj-http
* The response clauses used with the io.aviso.rook.client/then macro have changed structure, and now resemble
  similar clauses elsewhere (such as clojure.core.async's alt!).

The io.aviso.rook.client namespace has simplified slightly, and changed slightly, but this
may affect existing code.

First off, the way the `to` function creates the :uri key has changed, it no longer includes
the a leading slash. This will affect handlers.

Prior releases also did some logging of the Ring request before it is passed to the request handler,
and the Ring response received through the channel. This no longer occurs -- if this behavior is
desired, it can be built into the request handler.

In addition, the Ring response is now passed through unchanged; prior releases would remove
the Content-Length and Content-Type headers (for obscure reasons that no longer make sense).

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aclosed+milestone%3A0.1.19+)

## 0.1.18 - 25 Nov 2014

* Further improvements to making swagger optional.
* The "async loopback" support has been removed.
* The example used in the manual has been extensively revised.
* It is now possible to have multiple endpoint functions that match a single route; this is used
  for versioning and/or content negotiation.
* Dependencies have been updated.

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aclosed+milestone%3A0.1.18+)

## 0.1.17 - 5 Nov 2014

* swagger is now an optional dependency, and is only required if the :swagger option is enabled

[Closed issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aissue+milestone%3A0.1.17+is%3Aclosed)

## 0.1.16 - 24 Oct 2014

* Namespace paths can now be a simple string, as an alternative to a vector. E.g. `(rook/namespace-handler ["users" 'org.example.resources.users])`.
* The convention name for PUT :id has been renamed from `change` to `update`. `update` is still supported, but will be removed in a subsequent release.
* Improvements to the io.aviso.rook.client/then macro.
* Removed support for the :path-spec metadata.
* The :context-pathvec option was renamed to :context.
* The way synchronous handlers are wrapped into asynchronous handlers is now pluggable.
* As usual, keeping up with dependencies.

[Closed issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aissue+milestone%3A0.1.16+is%3Aclosed)

## 0.1.15 - 3 Oct 2014

* The `new` and `edit` convention names for endpoint functions were removed.
* The convention name for PUT :id has changed from `update` to `change` (to avoid future conflict with Clojure 1.7).
* The :route-spec metadata key has been renamed to just :route.
* A new and very alpha integration with [ring-swagger](https://github.com/metosin/ring-swagger) has been introduced.

[Closed issues](https://github.com/AvisoNovate/rook/issues?q=is%3Aissue+milestone%3A0.1.15+is%3Aclosed)

## 0.1.14 - 15 Aug 2014

A critical bug related to dispatch and route parameters has been identified and fixed.

[Closed issues](https://github.com/AvisoNovate/rook/issues?q=milestone%3A0.1.14+is%3Aclosed)

## 0.1.13 - 28 Jul 2014

This release addresses a few minor issues related to reporting errors. 
Importantly, when using response validation, any 5xx error responses 
(usually indicating a failure inside the response handler function, or downstream from the function) are passed through unchanged.

[Closed issues](https://github.com/AvisoNovate/rook/issues?q=milestone%3A0.1.13+is%3Aclosed)

## 0.1.12 - 21 Jul 2014

This release updates a few dependencies, and adds additional debugging inside the io.aviso.rook.dispatcher namespace:

* With debug enabled, there is a message identifying how each incoming request is matched to a function
* With trace enabled, there is a message (at startup) identifying the merged metadata for each endpoint function

The merged metadata is the merge of the function's metadata with the containing namespace's. An attempt is made to eliminate common keys (such as :doc, :line, etc.) so that it's just the custom metadata provided on the function itself (or inherited from the namespace).

This extra debugging is very handy for diagnosing issues such as "is it invoking my handler?" or "why is my middleware not getting invoked?".

No issues were closed in this release.

## 0.1.11 - 8 Jul 2014

This release significantly revamped argument resolvers, including making the list of argument resolvers extensible using options to the namespace-handler, and via :arg-resolvers metadata on functions and namespaces.

Middleware for namespaces is no longer simple Ring middleware; the middleware is passed both the handler to wrap and the merged metadata for the endpoint function. This encourages the middleware to only wrap handlers for which it applies, leading to improved runtime efficiency. A new function, compose-middleware makes it easy to string together several middleware expressions, similar to how -> is used for Ring middleware.

In addition, it is now possible to add metadata defining response status code and corresponding body schemas; this is useful in development to ensure that your endpoint functions are returning the values you expect.

No issues were closed for this release.

## 0.1.10 - 1 Jul 2014

The major change in this release is the introduction of a new dispatcher system that scales larger and operates more efficiently than Compojure; in fact Compojure and Clout are no longer dependencies of Rook.

[Documentation for Rook](http://howardlewisship.com/io.aviso/documentation/rook) has been greatly expanded and moved out of the project.

We've also gone a long way towards improved efficiency; there's a new and improved system for matching endpoint function arguments to a _resolver_ that provides the value for the argument. This is now done computed once, when building the dispatcher, rather than computed by a search every time a endpoint function is invoked.

Expect some more changes in 0.1.11 that close the final loops in dynamic argument resolution, as well as making the argument resolution more extensible.

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=milestone%3A0.1.11+is%3Aclosed)

## 0.1.9 - 15 May 2014

This release refines the async support in Ring considerably; it replaces the odd 'return `false`' behavior for asynchronous handlers with the more traditional 'return `nil`' (to close the result channel).

Synchronous handlers are now explicitly invoked in a new `thread` block; previously they may have been executed inside a `go` block thread.

There's new middleware for supporting Ring sessions in a fully async pipeline.

*All* endpoint function arguments are resolved uniformly via the `:arg-resolvers` list; this includes previously hard-coded arguments such as `request`.

The default list of argument resolvers now includes the ability to resolve Ring request headers, for example: An argument named `content-type` will map to the `"content-type"` Ring request header.

Endpoint function arguments may now be destructured maps; as long as the `:as` keyword is there (to provide a name for argument resolution). This is useful for extracting a large number of values from the Ring request `:params` map.

A default argument resolver for `params*` will resolve to the same as `params`, but with the map keys _Clojurized_ (underscores replaced with dashes).

A default argument resolver for `resource-uri` has been added; this is typically used to supply a `Location` header in a response.

Schema cooercion now understands converting strings to booleans, instants, and UUIDs. Schema validation reporting is better, but still a work in progress. Validation has been simplified; there's no longer any attempt to 'shave' the parameters to match the schema, so you will often need to add a mapping of `s/Any` to `s/Any` to prevent spurious failures (from arbitrary query parameters, for example).

__Note: there have been a number of refactorings; a few functions have been renamed, and in some places, key/value varargs have been replaced with a simple map.__

* namespace-middleware --> wrap-namespace
* arg-resolver-middleware --> wrap-with-arg-resolvers

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=milestone%3A0.1.9+is%3Aclosed)

## 0.1.8 - 31 Mar 2014

This release introduces a significant new feature: Asynchronous request processing using [core.async](https://github.com/clojure/core.async). At its core is the definition of an _asynchronous handler_ (or middleware), which accepts a Ring request map and returns its response inside a channel; asynchronous handlers are typically implemented using `go` blocks.

Resources within the same server will often need to cooperate; Rook supports this via the _asynchronous loopback_, which is a way for one resource to send a request to another resource using core.async conventions (and without involving HTTP or HTTPs).

There's also support for leveraging Jetty continuations so that all request handling is fully non-blocking, end-to-end.

Other features:

*  Validation of incoming request parameters using [Prismatic Schema](https://github.com/prismatic/schema)
* `io.aviso.rook.client` namespace to streamline cooperation between resources via the asynchronous loopback
* Metadata from the containing namespace is merged into metadata for individual endpoint functions

[Closed Issues](https://github.com/AvisoNovate/rook/issues?q=milestone%3A0.1.8+is%3Aclosed)

