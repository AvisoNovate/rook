io.aviso/rook
=============

.. image:: http://clojars.org/io.aviso/rook/latest-version.svg
   :alt: Clojars Project
   :target: http://clojars.org/io.aviso/rook

Easier Routing for Pedestal
---------------------------

Rook is a way of mapping Clojure namespaces and functions as the endpoints of a
`Pedestal <https://github.com/pedestal/pedestal>`_ application.

Using Rook, you map a namespace to a URI; Rook uses metadata to identify which functions are endpoints.
It generates a
`Pedestal routing table <https://github.com/pedestal/pedestal/blob/master/guides/documentation/service-routing.md#routing>`_
that you can use as-is, or combine with more traditional routing.

With Rook, your configuration is both less dense and more dynamic, because you only have to explicitly identify
your namespaces and Rook finds all the endpoints within those namespaces.

Rook is also designed to work well the `Component <https://github.com/stuartsierra/component>`_ library, though
Component is not a requirement.


Rook generates a set of table routes that can then be used by the io.pedestal.http/create-server bootstrapping function.

.. code-block:: clojure

    (require '[io.aviso.rook :as rook]
              [io.pedestal.http :as http])

    (def service-map
      {:env :prod
       ::http/routes (rook/gen-table-routes {"/widgets" 'org.example.widgets
                                             "/gizmos"  'org.example.gizmos}
                                            nil}
       ::http/resource-path "/public"
       ::http/type :jetty
       ::http/port 8080})

    (defn start
      []
      (-> service-map
          http/create-server
          http/start))

Rook supports many more options:

- Nested namespaces

- Defining interceptors for endpoints

- Leveraging metadata at the namespace and function level

- Defining constraints for path parameters

License
-------

Rook is released under the terms of the `Apache Software License 2.0 <http://www.apache.org/licenses/LICENSE-2.0>`_.

.. toctree::
   :hidden:

   endpoints
   nested
   interceptors
   async
   arg-resolvers

   API <http://avisonovate.github.io/docs/rook>
   GitHub Project <https://github.com/AvisoNovate/rook>

