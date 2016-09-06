Nested Namespaces
=================

Defining nested (that is, hierarchical) namespaces requires a smidgen of extra work.
In the non-nested case, it is usually sufficient to just specify the namespace (as a symbol),
but with nested namespaces, a map is used; this is the full namespace definition.

.. code-block:: clojure

    (rook/gen-table-routes {"/hotels" {:ns 'org.example.hotels
                                       :nested {"/:hotel-id/rooms" 'org.example.rooms}}}
                           nil)

In this example, the outer namespace is mapped to ``/hotels`` and the nested rooms
namespace is mapped to ``/hotels/:hotel-id/rooms`` ... in other words, whenever we
access a specific room, we must also provide the hotel's id in the URI.

:nested is just a new mapping of paths to namespaces under ``/hotels``; the map keys
are extensions to the path, and the values can be namespace symbols or nested namespace definitions.

Namespace Inheritance
---------------------

Nested namespaces may inherit some data from their containing namespace:

* :arg-resolvers - a map from keyword to :doc:`argument resolver factory <arg-resolvers>`

* :interceptors - a vector of :doc:`Pedestal interceptors <interceptors>`

* :constraints - a map from keyword to regular expression, for path parameter constraints

These options flow as follows:

.. graphviz::

    digraph {
        defaults [label="io.aviso.rook/default-options"];
        opts [label="gen-table-routes options"];
        nsdef [label="outer namespace definition"];
        nsmeta [label="outer namespace metadata"];
        nesteddef [label="nested namespace definition"];
        nestedmeta [label="nested namespace metadata"];
        nestedfns [label="nested endpoint function metadata"];

        fmeta [label="endpoint function metadata"];

        defaults -> opts -> nsdef -> nsmeta -> nesteddef -> nestedmeta -> nestedfns;

        nsmeta -> fmeta;
    }


Metadata on an endpoint function is handled slightly differently,
:constraints overrides come from the third value in the :rook-route metadata.

In all cases, a deep merge takes place:

- nested maps are merged together, later overriding earlier

- sequences are concatenated together (using ``concat`` for sequences, or ``into`` for vectors)

This inheritance is quite useful: for example, the org.example.hotels namespace may
define a ``:hotel-id`` constraint that will be inherited by the org.example.rooms namespace endpoints.
