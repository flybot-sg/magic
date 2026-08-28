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
   magic.test.load)
  (:use clojure.test))

(defn- check-summary!
  "Throw when the run had failures or errors. clojure.test/run-tests only
   reports them in its return value, and nostrand ignores what a task returns,
   so throwing is the only way a red suite reaches the process exit code."
  [{:keys [fail error] :as summary}]
  (when (pos? (+ fail error))
    (throw (ex-info "test suite failed" (dissoc summary :type))))
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
    'magic.test.load)))

(defn run [& namespaces]
  (check-summary! (apply run-tests namespaces)))