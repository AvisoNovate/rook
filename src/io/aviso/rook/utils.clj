(ns io.aviso.rook.utils
  "Kitchen-sink of useful standalone utilities."
  (:import
    (java.util UUID))
  (:require
    [ring.util.response :as r]
    [ring.util.time :as t]
    [clojure.pprint :as pprint]))

(defn new-uuid
  "Generates a new UUID string, via UUIDrandomUUID."
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

(defn pretty-print
  "Pretty-prints the supplied object to a returned string."
  [object]
  (pprint/write object
                :stream nil
                :pretty true))

(defn pretty-print-brief
  "Prints the object more briefly, with limits on level and length."
  [object]
  (binding [*print-level* 4
            *print-length* 5] (pretty-print object)))

(defn summarize-method-and-uri
  [method uri]
  "Formats a method (a keyword, e.g. :get) and a URI into a single string."
  (format "%s `%s'"
          (-> method name .toUpperCase)
          uri))

(defn summarize-request
  "Returns a summary of the request: the :request-method and the :uri, formatted as a single string."
  [request]
  (summarize-method-and-uri (:request-method request) (:uri request)))

