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
   magic.test.fn
   magic.test.letfn
   magic.test.pipeline
   magic.test.hash
   magic.test.stdlib
   magic.test.spec
   magic.test.deterministic
   magic.test.flags
   magic.test.protocol
   magic.test.errors
   magic.test.load)
  (:use clojure.test))

(defn all []
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
   'magic.test.fn
   'magic.test.letfn
   'magic.test.pipeline
   'magic.test.hash
   'magic.test.stdlib
   'magic.test.spec
   'magic.test.deterministic
   'magic.test.flags
   'magic.test.protocol
   'magic.test.errors
   'magic.test.load))

(defn run [& namespaces]
  (apply run-tests namespaces))