(ns magic.test.spec
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]))

;; Call spec directly (not via m/eval) so these run the committed DLL, guarding
;; against a stale stdlib DLL after a runtime hasheq change.

(s/def ::a number?)

(deftest cat-op
  (is (true? (s/valid? (s/cat :a number? :b string?) [1 "x"])))
  (is (= {:a 1 :b "x"} (s/conform (s/cat :a number? :b string?) [1 "x"])))
  (is (false? (s/valid? (s/cat :a number? :b string?) [1 2])))
  (is (= '(1 "x") (s/unform (s/cat :a number? :b string?) {:a 1 :b "x"}))))

(deftest star-op
  (is (true? (s/valid? (s/* number?) [1 2 3])))
  (is (true? (s/valid? (s/* number?) [])))
  (is (= [1 2 3] (s/conform (s/* number?) [1 2 3])))
  (is (false? (s/valid? (s/* number?) [1 :x]))))

(deftest plus-op
  (is (false? (s/valid? (s/+ number?) [])))
  (is (true? (s/valid? (s/+ number?) [1])))
  (is (= [1 2] (s/conform (s/+ number?) [1 2]))))

(deftest question-op
  (is (true? (s/valid? (s/? number?) [])))
  (is (true? (s/valid? (s/? number?) [1])))
  (is (false? (s/valid? (s/? number?) [1 2])))
  (is (= 1 (s/conform (s/? number?) [1]))))

(deftest alt-op
  (is (= [:n 1] (s/conform (s/alt :n number? :s string?) [1])))
  (is (= [:s "x"] (s/conform (s/alt :n number? :s string?) ["x"])))
  (is (false? (s/valid? (s/alt :n number? :s string?) [:kw]))))

(deftest amp-op
  (is (true? (s/valid? (s/& (s/* number?) #(even? (count %))) [1 2])))
  (is (false? (s/valid? (s/& (s/* number?) #(even? (count %))) [1 2 3]))))

(deftest keys*-op
  (is (true? (s/valid? (s/keys* :req-un [::a]) [:a 1])))
  (is (= {:a 1} (s/conform (s/keys* :req-un [::a]) [:a 1]))))

(deftest explain-data-op
  (testing "op-explain reached through a failing regex op"
    (is (nil? (s/explain-data (s/cat :a number?) [1])))
    (is (some? (s/explain-data (s/cat :a number?) [:kw])))))
