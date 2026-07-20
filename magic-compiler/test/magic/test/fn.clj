(ns magic.test.fn
  (:require [clojure.test :refer [deftest]]
            [magic.api :as magic]
            [magic.flags :as flags])
  (:use magic.test.common))

(deftest invocation
  (cljclr=magic
   (let [f (fn [x] x)]
     (f :hello))
   (let [f (fn [x] (+ x 10))]
     (f 8))))

;; clojureclr does not support most of these hints
;; so we cant compare outputs
(deftest primitive-type-hints
  (clojure.test/is
   (= 18 (magic/eval
          '(let [f (fn [^int x] (+ x 10))]
             (f 8)))))
  (clojure.test/is
   (= Int32
      (type
       (magic/eval
        '(let [f (fn [^int x] x)]
           (f 8))))))
  (clojure.test/is
   (= 18 (magic/eval
          '(let [f (fn [^long x] (+ x 10))]
             (f 8)))))
  (clojure.test/is
   (= Int64
      (type 
       (magic/eval
        '(let [f (fn [^long x] (+ x 10))]
           (f 8)))))))

(deftest closures
  (cljclr=magic
   (let [x 90
         f (fn [y] (+ x y))]
     (f 88))
   (let [x 90
         f (fn [y] (let [z 71]
                     (+ x y z)))]
     (f 88))))

(deftest higher-order
  (cljclr=magic
   (let [x 90
         f (fn [y] (fn [z] (+ x y z)))
         g (f 11)]
     (g 63))))

;; Regression: defmacro with a prepost map used to crash at def-eval
;; with InvalidCastException via the implicit [&form &env ...] arglist.
(deftest defmacro-prepost
  (clojure.test/is
   (= 7 (magic/eval
         '(do (defmacro plus [a b] {:pre [(integer? a)]} (list '+ a b))
              (plus 3 4))))))

;; Regression: a defn arity whose only param is &form used to crash sigs
;; with ArgumentOutOfRangeException (subvec 2 1).
(deftest defn-form-only-param
  (clojure.test/is
   (= "x" (magic/eval
           '(do (defn only-form [&form] (str &form))
                (only-form "x"))))))

(deftest named-fn-self-reference
  (clojure.test/is
   (= [true true]
      (magic/eval
       '(let [f (fn me [k v] (if (nil? v) me v))]
          [(identical? f (f :a nil)) (= f (f :a nil))]))))
  (clojure.test/is
   (= [true true]
      (binding [flags/*direct-linking* true]
        (magic/eval
         '(let [f (fn me [k v] (if (nil? v) me v))]
            [(identical? f (f :a nil)) (= f (f :a nil))])))))
  (clojure.test/is
   (= true
      (binding [flags/*direct-linking* true]
        (magic/eval
         '(let [f (fn me [] (fn [] me))]
            (identical? f ((f))))))))
  ;; arity 2 keeps invokeStatic; the self-returning arity compiles as instance
  (clojure.test/is
   (= [true 5 [2]]
      (binding [flags/*direct-linking* true]
        (magic/eval
         '(do (defn self-arity ([v] (if (nil? v) self-arity v)) ([a b] (+ a b)))
              [(identical? self-arity (self-arity nil))
               (self-arity 2 3)
               (mapv #(count (.GetParameters %))
                     (filter #(= "invokeStatic" (.Name %))
                             (.GetMethods (type self-arity))))]))))))

(deftest fn-meta-elides-source-position
  (clojure.test/is
   (nil? (magic/eval '(meta (fn [] 1)))))
  (clojure.test/is
   (= {:cool true} (magic/eval '(meta ^{:cool true} (fn [] 1))))))
