(ns io.aviso.rook.utils
  "Kitchen-sink of useful standalone utilities."
  (:import
    (java.util UUID))
  (:require
    [ring.util.response :as r]
    [ring.util.time :as t]
    [clojure.pprint :as pprint]))

(defn new-uuid
  "Generates a new UUID string, via UUID/randomUUID."
  []
  (-> (UUID/randomUUID) .toString))

(defn response
  "Construct Ring response for success or other status.  If the status code is omitted, it defaults
  to 200.  If only the body is provided, but it is numeric, then it is treated as a status code with
  an empty body."
  ([body]
   (if (number? body)
     (response body nil)
     (r/response body)))
  ([status body] (->
                   (r/response body)
                   (r/status status))))

(defn date-header
  "Adds a date header to the response, using the RFC-1123 format."
  [response name date]
  (r/header response name (t/format-date date)))

;;; useful at the REPL
(defn pprint-code
  "Pretty prints the form using code indentation rules."
  [form]
  (pprint/write form :dispatch pprint/code-dispatch)
  (prn))

(defn summarize-method-and-uri
  "Formats a method (a keyword, e.g. :get) and a URI into a single string."
  [method uri]
  (format "%s `%s'"
          (-> method name .toUpperCase)
          uri))

(defn summarize-request
  "Returns a summary of the request: the :request-method and the :uri, formatted as a single string."
  [request]
  (summarize-method-and-uri (:request-method request) (:uri request)))
