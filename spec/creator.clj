(ns creator
  {:sync true}
  (:import (javax.servlet.http HttpServletResponse))
  (:require [ring.util.response :as r]))

(defn create
  [resource-uri]
  (println "creator/create resource-uri=" resource-uri)
  (-> (r/response nil)
      (r/status HttpServletResponse/SC_CREATED)
      (r/header "Location" (str resource-uri "<ID>"))))

