Interceptors
============

Pedestal is all about
`interceptors <https://github.com/pedestal/pedestal/blob/master/guides/documentation/service-interceptors.md>`_,
they are integral to how Pedestal applications are constructed and composed.

Each endpoint function may define a set of interceptors specific to that function, using
:interceptors metadata.

Interceptors may be :doc:`inherited <nested>` from the namespace and elsewhere.

Interceptor Values
------------------

The interceptor values can be any of the values that Pedestal accepts as an interceptor:

* An interceptor, as by io.pedestal.interceptor/interceptor.

* A map, which is converted to an interceptor

* A bunch of other things that make sense in terms of Pedestal's deprecated `terse` routing syntax

Rook adds one additional option, a keyword.

The keyword references the :interceptor-defs option (passed to io.aviso.rook/gen-table-routes).

A matching value must exist (otherwise, an exception is thrown).

The value is typically a configured interceptor value.

Alternately, the value might be an interceptor generator: a function with metadata :endpoint-interceptor-fn.

Interceptor Generators
----------------------

An interceptor generator is a function that creates an interceptor customized to the particular endpoint.

It is passed a map that describes the endpoint, and returns an interceptor.


The endpoint description has the following keys:

:var
    The Clojure Var containing the endpoint function.

:meta
    The metadata map for the endpoint function.

:endpoint-name
    A string version of the fully qualified name of the var

In this way, the interceptor can use the details of the particular endpoint to generate a custom interceptor.

For example, an interceptor that did some special validation, or authentication, might use metadata on
the endpoint function to determine what validations and authentications are necessary for that particular
endpoint.

For example, part of Rook's test suite, uses this Interceptor generator:

.. literalinclude:: ../spec/sample/dynamic_interceptors.clj
   :language: clojure


Applying Interceptors
---------------------

When a namespace provides :interceptor metadata, that's a list of interceptors to add to every endpoint
in the namespace, and in any nested namespaces.

This can cast a wider net than is desirable.

However, each individual endpoint will ultimately end up with its own individual interceptor list.

Further, none of those interceptors will actually execute in a request unless routing selects that
particular endpoint to handle the request.

.. graphviz::

    digraph {
        incoming [label="Incoming Request"];
        definterceptors [label="Default Interceptors"];
        routing [label="Pedestal Routing"];
        endpoint [label="Endpoint Selected"];
        interceptors [label="Endpoint's Interceptors"];
        fn [label="Endpoint Function"];

        incoming -> definterceptors -> routing -> endpoint -> interceptors -> fn;

    }


The default interceptors are usually provided by io.pedestal.http/create-server.
These cover a number of cases such as handling requests for unmapped URIs,
logging, preventing cross-site scripting forgery, and so forth.

The :interceptor metadata in namespaces and elsewhere simply builds up the Endpoint's Interceptors
stage.
