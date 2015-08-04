 (ns explicit-arg-resolver
  "Used to demonstrate the use of rook/resolve-argument to resolve a specific argument's value."
  (:require [io.aviso.rook :as rook]
            [io.aviso.rook.utils :as utils]))

 (defn keyword-example
  {:route [:get ["keyword-example"]]}
  [request]
  (utils/response {:value (rook/resolve-argument-value request :header 'special-header)}))

 (defn symbol-example
  {:route [:get ["symbol-example" :*]]}
  [request]
  (utils/response {:value (rook/resolve-argument-value request nil 'wildcard-path)}))
