Asynchronous Endpoints
======================

There isn't much to say here: an endpoint can be asynchronous by returning
a `Clojure core.async <https://github.com/clojure/core.async>`_ channel instead of a Ring response map.

The channel must convey a Ring response map.

Really, Pedestal takes care of the rest.

