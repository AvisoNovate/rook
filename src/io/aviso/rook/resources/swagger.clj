(ns io.aviso.rook.resources.swagger
  "Exposes a resource used to access the Swagger description for the mapped namespaces (excluding this one, and any other
  endpoints with the :no-swagger metadata."
  {:no-swagger true
   :added      "0.1.27"}
  (:require [ring.util.response :as res]
            [cheshire.core :as json]))

(defn swagger-json
  "Returns the Swagger API description as (pretty) JSON."
  {:route [:get "swagger.json"]}
  [swagger-object]
  ;; It's a bit silly to stream this to JSON on each request; a cache would be nice. Later.
  ;; Don't want to rely on outer layers providing the right middleware, so we do the conversion
  ;; to JSON right here.
  (-> swagger-object
      (json/generate-string {:pretty true})
      res/response
      (res/content-type "application/json")))
