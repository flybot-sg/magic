(ns magic.test.compare
  (:require [clojure.test :refer [deftest is]]))

;; Ground-truth orderings from JVM Clojure 1.12: string, symbol, and keyword
;; comparison is by UTF-16 code unit, never the OS collation rules.
(deftest compare-strings-matches-jvm
  (is (= ["A" "B" "a" "b"] (sort ["a" "B" "b" "A"])))
  (is (= ["e" "f" "é"] (sort ["e" "é" "f"])))
  (is (= ["" "a" "b"] (sort ["b" "" "a"])))
  (is (pos? (compare "a" "B")))
  (is (neg? (compare "B" "a")))
  ;; str concatenation defeats literal interning, so this exercises the
  ;; ordinal comparison returning 0 rather than the identity short-circuit
  (is (zero? (compare "ab" (str "a" "b")))))

(deftest compare-symbols-and-keywords-matches-jvm
  (is (= '[A B a b] (sort '[a B b A])))
  (is (= '[X/a x/B x/a] (sort '[x/a X/a x/B])))
  (is (= [:A :B :a :b] (sort [:a :B :b :A])))
  (is (pos? (compare 'a 'B)))
  (is (pos? (compare :a :B)))
  ;; nil-namespace vs namespaced branches, both directions
  (is (neg? (compare 'a 'a/a)))
  (is (pos? (compare 'a/a 'a)))
  ;; equal namespaces fall through to the name comparison
  (is (pos? (compare 'x/a 'x/B))))

(deftest compare-non-string-fall-through
  ;; the string special case must not disturb other IComparable types
  (is (neg? (compare false true)))
  (is (pos? (compare \b \a))))

(deftest sorted-set-order-matches-jvm
  (is (= ["A" "B" "a" "b"] (seq (sorted-set "a" "B" "b" "A")))))
