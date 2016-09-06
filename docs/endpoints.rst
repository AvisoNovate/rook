Defining Endpoints
==================

The io.aviso.rook/gen-table-routes function is provided with namespaces; the actual
endpoints are functions within those namespaces.

Rook identifies functions with the metadata :rook-route.
Functions with this metadata will be added to the generated routing table.

Here's an example namespace with just a single endpoint:

.. code-block:: clojure

    (ns org.example.widgets
      (:require [ring.util.response :as r])

    (defn list-widgets
      {:rook-route [:get ""]}
      []
      ;; Placeholder:
      (r/response {:widgets []})

Endpoint functions take some number of parameters (more on this shortly) and return
a `Ring response map <https://github.com/ring-clojure/ring/blob/master/SPEC#L108>`_.

The above example is a placeholder: it returns a fixed and largely empty Ring response.
In a real application, the function could be provided with a database connection of some sort
and could perform a query and return the results of that query.

.. note::

    Pedestal route matching is very specific: the ``list-widgets`` endpoint above is mapped to ``/widgets``,
    and a client that requests ``/widgets/`` will get a 404 NOT FOUND response.

Rook Routes
-----------

The route meta is either two or three terms, in a vector:

* The verb to use, such as :get, :post, :head, ... or :all.

* The path to match.

* (optional) A map of path variable constraints.

For example, we might define some additional endpoints to flesh out a typical resource-oriented API:

.. code-block:: clojure

    (defn get-widget
      {:rook-route [:get "/:id" {:id #"\d{6}"}]}
      [^:path-param id]
      ;; Placeholder:
      (r/not-found "WIDGET NOT FOUND"))

    (defn update-widget
      {:rook-route [:post "/:id" {:id #"\d{6}"}]}
      [^:path-param id ^:request body]
      ;; Placeholder:
      (r/response "OK"))

The URI for the ``get-widget`` endpoint is ``/widgets/:id``, where the ``:id`` path parameter
must match the regular expression (six numeric digits).

This is because the namespace's URI is ``/widgets`` and the endpoint's path is directly appended to that.
Likewise, the ``update-widget`` endpoint is also mapped to the URI ``/widgets/:id``, but with the POST verb.

Because of how Pedestal routing is designed, a URI  where the ``:id`` variable doesn't match the regular expression
will be ignored (it might match some other endpoint, but will most likely match nothing and result in a
404 NOT FOUND response).

This example also illustrates another major feature of Rook: endpoints can have any number of parameters,
but use metadata on the parameters to identify what is to be supplied.

Two common parameter metadata are used in the above example:

* :path-param is used to mark function parameters that should match against a path parameter.

* :request is used to mark function parameters that should match a key stored in the Ring request map.

Rook defines additional parameter metadata, and they are extensible.

Namespace Metadata
------------------

That repetition about the ``:id`` path parameter constraint is bothersome, having it multiple
places just makes it more likely to have conflicts.

Fortunately, Rook merges metadata from the namespace with metadata from the endpoint, allowing
such things to be just defined once:


.. code-block:: clojure
   :emphasize-lines: 2

    (ns org.example.widgets
      {:constraints {:id #"\d{6}"}}
      (:require [ring.util.response :as r])

    (defn list-widgets
      {:rook-route [:get ""]}
      []
      ;; Placeholder:
      (r/response {:widgets []})

    (defn get-widget
      {:rook-route [:get "/:id"]}
      [^:path-param id]
      ;; Placeholder:
      (r/not-found "WIDGET NOT FOUND"))

    (defn update-widget
      {:rook-route [:post "/:id"]}
      [^:path-param id ^:request body]
      ;; Placeholder:
      (r/response "OK"))

Here, each endpoint inherits the ``:id`` constraint from the namespace.

.. note:

  It is not necessary to define a constraint for every path parameter, but it
  can be beneficial.

Route Names
-----------

When Rook creates an interceptor, it provides a name for the interceptor;
this is the keyword version of the fully qualified endpoint name.

The interceptor name is the default route name, used by Pedestal when it
create URLs.

You can override the route name using the :route-name metadata on the endpoint
function.


