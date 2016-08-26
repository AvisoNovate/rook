Nested Namespaces
=================

Nested namespaces is relatively straight forward:

.. code-block:: clojure

    (rook/gen-table-routes {"/hotels" {:ns 'org.example.hotels
                                       :nested {"/:hotel-id/rooms" 'org.example.rooms}}}
                           nil)

In this example, the outer namespace is mapped to ``/hotels/`` and the nested rooms
namespace is mapped to ``/hotels/:hotel-id/rooms`` ... in other words, whenever we
access a room, we are also providing the hotel's id in the URI.

Instead of providing the namespace, a namespace definition is provided, which
includes the namespace and nested namespaces are packaged together.
(being able to just provide the namespace is a convenience).

Namespace Inheritance
---------------------

Nested namespaces may inherit some data from their containing namespace:

* :arg-resolvers - a map from keyword to argument resolver factory

* :interceptors - a vector of interceptors

* :constraints - a map from keyword to regular expression, for constraints

These options flow as follows:

.. graphviz::

    digraph {
        defaults [label="io.aviso.rook/default-options"];
        opts [label="gen-table-routes options"];
        nsdef [label="outer namespace definition"];
        nsmeta [label="outer namespace metadata"];
        nesteddef [label="nested namespace definition"];
        nestedmeta [label="nested namespace metadata"];
        nestedfns [label="nested endpoint functions"];

        fmeta [label="endpoint function metadata"];

        defaults -> opts -> nsdef -> nsmeta -> nesteddef -> nestedmeta -> nestedfns;

        nsmeta -> fmeta;
    }

At the function meta data, :arg-resolvers and :interceptors are handled uniformly, but
:constraints overrides come from the third value in the :rook-route metadata.

In all cases, a deep merge takes place:

- nested maps are merged together, later overriding earlier

- sequences are concatenated together (using ``concat`` for sequences, or ``into`` for vectors)

This inheritance is quite useful: for example, the org.example.hotels namespace may
define a ``:hotel-id`` constraint that will be inherited by the org.example.rooms namespace endpoints.
