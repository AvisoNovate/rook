(ns resolved-conflict)

(defn match-version [v] #(-> % :params :v (= v)))
(def version-1 (match-version "1"))
(def version-2 (match-version "2"))

(defn first-match
  {:route [:get [:id]]
   :match version-1}
  [id]
  {:status 200 :body {:matched "first-match" :id id}})

(defn second-match
  {:route [:get [:id]]
   :match version-2}
  [id]
  {:status 200 :body {:matched "second-match" :id id}})