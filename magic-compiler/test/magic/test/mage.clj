(ns magic.test.mage
  (:require [mage.core :as il]
            clojure.test))

;;; every short arity of il/type reaches the full one

(def ^:private full
  (il/type "T" System.Reflection.TypeAttributes/Public [] System.Object nil [] []))

(clojure.test/deftest test-type-short-arities
  (clojure.test/is (= full (il/type "T")))
  (clojure.test/is (= full (il/type "T" [])))
  (clojure.test/is (= full (il/type "T" [] [])))
  (clojure.test/is (= full (il/type "T" System.Reflection.TypeAttributes/Public [] [])))
  (clojure.test/is (= full (il/type "T" System.Reflection.TypeAttributes/Public [] System.Object []))))
