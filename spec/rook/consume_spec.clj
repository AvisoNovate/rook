(ns rook.consume-spec
  "Tests for the consume macro."
  (:use speclj.core
        io.aviso.rook.internals))

(describe "io.aviso.rook.internals/consume"

  (it "allows for an empty bindings vector"
      (should= :ok
               (consume () [] :ok)))

  ;; The following tests, which use eval, fail outside the REPL, and I don't know why yet.
  (it "ensures that the correct number of bindings terms are provided"
      (pending)
      (should-throw Exception "Incorrect number of binding terms for consume."
                    (eval '(consume () [alpha zero?] :unreachable))))

  (it "ensures that :& predicate is last with no arity"
      (pending)
      (should-throw Exception "Expected just symbol and :& placeholder as last consume binding."
                    (eval '(consume (range 100) [xs :& :*]))))

  (it "requires a known arity"
      (pending)
      (should-throw Exception "Unknown arity in consume binding. Expected :one, :?, :*, or :+."
                    (eval '(consume (range 100) [xs even? :a-bunch]))))

  (it "handles :& as a special predicate that gets the remaining elements"
      (should= {:alpha [1 2 3]
                :beta  (range 4 20)}
               (consume (range 1 20)
                        [alpha #(< % 4) :*
                         beta :&]
                        {:alpha alpha :beta beta})))

  (it "allow non-matches for :*"
      (should= {:alpha ()
                :beta  (range 1 20)}
               (consume (range 1 20)
                        [alpha even? :*
                         beta :&]
                        {:alpha alpha :beta beta})))

  (it "is an exception if :+ matches none"
      (should-throw Exception #"Expected to consume at least one match with :\+ arity"
                    (consume (range 1 20)
                             [alpha even? :+
                              beta :&]
                             {:alpha alpha :beta beta})))

  (it "should match a single value for :one"
      (should= {:alpha 1
                :beta  (range 2 20)}
               (consume (range 1 20)
                        [alpha #(< % 4) :one
                         beta :&]
                        {:alpha alpha :beta beta})))

  (it "allows a 1 instead of :one"
      (should= {:alpha 1
                :beta  (range 2 20)}
               (consume (range 1 20)
                        [alpha #(< % 4) 1
                         beta :&]
                        {:alpha alpha :beta beta})))

  (it "is an exception if :one does not match"
      (should-throw Exception #"consume :one arity did not match"
                    (consume (range 1 20)
                             [alpha even? :one
                              beta :&]
                             {:alpha alpha :beta beta})))

  (it "should match a single value for :?"
      (should= {:alpha 1
                :beta  (range 2 20)}
               (consume (range 1 20)
                        [alpha #(< % 4) :?
                         beta :&]
                        {:alpha alpha :beta beta})))

  (it "should bind a nil if the first collection value doesn't match with :?"
      (should= {:alpha nil
                :beta  (range 1 20)}
               (consume (range 1 20)
                        [alpha even? :?
                         beta :&]
                        {:alpha alpha :beta beta})))

  (it "should bind the next value from the collection for the special :+ predicate"
      (should= {:alpha 1
                :beta (range 2 20)}
               (consume (range 1 20)
                 [alpha :+
                  beta :&]
                 {:alpha alpha :beta beta})))

  (it "should throw an error if the collection is empty for the :+ predicate"
      (should-throw Exception "consume :+ predicate on empty collection"
                    (consume (range 1 20)
                      [alpha #(< % 20) :+
                       beta :+]
                      {:alpha alpha :beta beta}))))

(run-specs)