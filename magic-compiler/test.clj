(ns test
  (:require
   magic.test.literals
   magic.test.data-structures
   magic.test.string
   magic.test.logic
   magic.test.control
   magic.test.numbers
   magic.test.interop
   magic.test.dynamic
   magic.test.special
   magic.test.proxy
   magic.test.reify
   magic.test.deftype
   magic.test.fn
   magic.test.letfn
   magic.test.pipeline
   magic.test.intrinsics
   magic.test.hash
   magic.test.compare
   magic.test.stdlib
   magic.test.spec
   magic.test.deterministic
   magic.test.flags
   magic.test.protocol
   magic.test.errors
   magic.test.load
   magic.test.nostrand
   magic.test.mage)
  (:use clojure.test))

(defn- check-summary!
  "Exit -1 on any failure or error, since a task's return value does not
   set the exit code."
  [summary]
  (when-not (successful? summary)
    (Environment/Exit -1))
  summary)

(defn all []
  (check-summary!
   (run-tests
    'magic.test.literals
    'magic.test.data-structures
    'magic.test.string
    'magic.test.logic
    'magic.test.control
    'magic.test.numbers
    'magic.test.interop
    'magic.test.special
    'magic.test.dynamic
    'magic.test.proxy
    'magic.test.reify
    'magic.test.deftype
    'magic.test.fn
    'magic.test.letfn
    'magic.test.pipeline
    'magic.test.intrinsics
    'magic.test.hash
    'magic.test.compare
    'magic.test.stdlib
    'magic.test.spec
    'magic.test.deterministic
    'magic.test.flags
    'magic.test.protocol
    'magic.test.errors
    'magic.test.load
    'magic.test.nostrand
    'magic.test.mage)))

(defn run [& namespaces]
  (check-summary! (apply run-tests namespaces)))