(ns magic.test.intrinsics
  (:require [clojure.test :refer [deftest is testing]]
            [magic.analyzer :as ana]
            [magic.emission :as emission]))

(defn- fn-body
  "Analyze a (fn ...) form and return the body node of its single method.
  Analyzing fn forms creates their types eagerly, hence the module binding."
  [form]
  (let [ast (binding [emission/*module* (emission/fresh-module "intrinsics-test")]
              (ana/analyze form))]
    (is (= :fn (:op ast)) (str (:exception ast)))
    (-> ast :methods first :body)))

;; :inline expansion always runs; the pass recognizes the lowered static
;; method and takes the intrinsic when the argument types qualify. When they
;; do not, the call keeps the inline, never a Var invoke of the original fn.
(deftest declined-intrinsic-keeps-its-inline
  (testing "nth on an untyped arg stays the RT.nth static call"
    (let [body (fn-body '(fn [p] (nth p 0)))]
      (is (= :static-method (:op body)))
      (is (= "nth" (.Name (:method body))))))
  (testing "n-ary arithmetic on untyped args stays nested Numbers calls"
    (let [body (fn-body '(fn [a b c] (+ a b c)))]
      (is (= :static-method (:op body)))
      (is (= :static-method (-> body :args first :op))))))

(deftest qualifying-types-take-the-intrinsic
  (testing "through the lowered static method"
    (is (= :intrinsic (:op (:body (ana/analyze '(let [x 1] (+ x 1))))))))
  (testing "nested n-ary lowerings collapse bottom-up"
    (let [body (:body (ana/analyze '(let [x 1 y 2 z 3] (+ x y z))))]
      (is (= :intrinsic (:op body)))
      (is (= :intrinsic (-> body :args first :op))))))

;; Numbers.unchecked_inc lowers from both inc under *unchecked-math* and
;; unchecked-inc; the origin var keeps their different promotion behavior
(deftest one-lowering-two-vars-keeps-per-var-semantics
  (testing "inc promotes 32-bit operands to long even under unchecked math"
    (binding [*unchecked-math* true]
      (let [body (fn-body '(fn [] (inc Int32/MaxValue)))]
        (is (= :intrinsic (:op body)))
        (is (= Int64 (:type body))))))
  (testing "unchecked-inc keeps the operand type, so loop counters stay put"
    (let [body (fn-body '(fn [] (unchecked-inc (int 5))))]
      (is (= :intrinsic (:op body)))
      (is (= Int32 (:type body))))))

(deftest declined-intrinsic-behavior
  (is (= :b ((fn [p] (nth p 1)) [:a :b])))
  (is (= :d ((fn [p] (nth p 5 :d)) [:a])))
  (is (= 6 ((fn [a b c] (+ a b c)) 1 2 3)))
  (is (true? ((fn [a b] (= a b)) "xy" (str "x" "y"))))
  (is (true? ((fn [a b] (< a b)) 1 2))))

(deftest nth-on-array-keeps-not-found-semantics
  ;; 3-arg nth lowers to the 3-arg RT.nth, which bounds-checks; only the
  ;; 2-arg lowering is the ldelem intrinsic
  (is (= :d ((fn [^|System.Int32[]| a] (nth a 5 :d)) (int-array 3))))
  (is (= 7 ((fn [^|System.Int32[]| a] (nth a 0)) (int-array [7 8])))))

(deftest declined-intrinsic-without-inline-keeps-the-invoke
  ;; deref has no :inline meta, so the declined path stays a plain invoke
  (testing "no :inline meta"
    (is (= :invoke (:op (fn-body '(fn [p] (deref p))))))
    (is (= 42 ((fn [p] (deref p)) (atom 42)))))
  ;; (+ x) is outside +'s :inline-arities, so no expansion exists
  (testing "arity outside :inline-arities"
    (is (= :invoke (:op (fn-body '(fn [p] (+ p))))))
    (is (= 5 ((fn [p] (+ p)) 5)))))
