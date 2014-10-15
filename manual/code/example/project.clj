(def minimal-project (slurp "minimal-project.clj"))
(def spec-additions (slurp "spec-project.clj"))
(def project (clojure.string/replace
    minimal-project
    #"\)$"
    spec-additions))

(eval (read-string project))