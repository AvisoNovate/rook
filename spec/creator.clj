(ns creator
  (:require [ring.util.response :as r])
  (:import [javax.servlet.http HttpServletResponse]))

(defn create
  [resource-uri]
  (-> (r/response nil)
      (r/status HttpServletResponse/SC_CREATED)
      (r/header "Location" (str resource-uri "<ID>"))))

