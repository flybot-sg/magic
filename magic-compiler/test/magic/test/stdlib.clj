(ns magic.test.stdlib
  (:require [clojure.test :refer [deftest testing]])
  (:use magic.test.common))

;;; symbol (1.10): arity-1 works on strings, keywords, and vars

(deftest test-symbol
  (cljclr=magic
   (symbol 'already-a-symbol)
   (symbol "plain-string")
   (symbol "ns" "name")
   (symbol :kw)
   (symbol :ns/kw)
   (symbol #'clojure.core/map)
   (namespace (symbol :ns/kw))
   (name (symbol #'clojure.core/map))
   (namespace (symbol #'clojure.core/map))))

(deftest test-symbol-no-conversion
  ;; m/eval invokes the compiled form reflectively, wrapping throws in
  ;; TargetInvocationException, so assert on the directly-invoked fn instead.
  (clojure.test/is (thrown? ArgumentException (symbol 1))))

;;; read+string (1.10)

(defn- lntr [^String s]
  (clojure.lang.LineNumberingTextReader. (System.IO.StringReader. s)))

(deftest test-read+string
  (let [rdr (lntr "(+ 1 2) foo")]
    (clojure.test/is (= ['(+ 1 2) "(+ 1 2)"] (read+string rdr false nil)))
    (clojure.test/is (= ['foo "foo"] (read+string rdr false nil))))
  (testing "captured string survives the read and resets each call"
    (let [rdr (lntr "  :a\n:b  ")]
      (clojure.test/is (= [:a ":a"] (read+string rdr false nil)))
      (clojure.test/is (= [:b ":b"] (read+string rdr false nil)))))
  (testing "opts arity (read-cond)"
    (let [rdr (lntr "42")]
      (clojure.test/is (= [42 "42"] (read+string {:eof nil} rdr))))))

;;; tap> / add-tap / remove-tap (1.10)

(deftest test-tap
  (let [seen (atom [])
        f (fn [x] (swap! seen conj x))]
    (clojure.test/is (nil? (add-tap f)))
    (clojure.test/is (true? (tap> 42)))
    (clojure.test/is (true? (tap> nil)))
    (System.Threading.Thread/Sleep 500)
    (clojure.test/is (nil? (remove-tap f)))
    (clojure.test/is (= [42 nil] @seen))
    (testing "removed tap no longer receives values"
      (reset! seen [])
      (tap> :after-remove)
      (System.Threading.Thread/Sleep 200)
      (clojure.test/is (= [] @seen)))))
