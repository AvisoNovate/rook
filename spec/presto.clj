 (ns presto)


(defn show
  "In this test, the :resolver-factories option is extended to add :magic."
  [id ^:magic extra]
  (str id " -- " extra))