(ns unresolved-conflict
  "Namespace with an unresolved endpoint conflict.")

(defn first-match
  {:route [:get [:id]]}
  [id]
  {:status 200 :body {:matched "first-match" :id id}})

(defn second-match
  {:route [:get [:id]]}
  [id]
  {:status 200 :body {:matched "second-match" :id id}})
