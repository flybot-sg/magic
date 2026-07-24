(ns magic.test.literals
  (:require [clojure.test :refer [deftest]])
  (:use magic.test.common))

 (deftest constants
   (cljclr=magic 2)
   (cljclr=magic 2.0)
   (cljclr=magic 2e9)
   (cljclr=magic "hello")
   (cljclr=magic :hello))

(deftest tagged-literals
  (cljclr=magic #inst "2007-10-16T00:00:00.000-00:00")
  (cljclr=magic #uuid "3b8a31ed-fd89-4f1b-a00f-42e3d60cf5ce")
  (cljclr=magic [#inst "2020-01-02T03:04:05.678-00:00"
                 #uuid "00000000-0000-0000-0000-000000000001"]))

(deftest unsigned-constants
  (cljclr=magic UInt32/MaxValue)
  (clojure.test/is (= "4294967295" (str UInt32/MaxValue)))
  (cljclr=magic UInt64/MaxValue)
  (clojure.test/is (= "18446744073709551615" (str UInt64/MaxValue))))

(deftest sets
  (cljclr=magic #{}))

(deftest maps
  (cljclr=magic {}))

(deftest lists
  (cljclr=magic []))