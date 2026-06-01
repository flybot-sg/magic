(ns magic.test.flags
  (:require [clojure.test :refer [deftest testing is]]
            [magic.core :as magic]
            [magic.flags :as flags]
            ;; loaded so active-spells' find-var resolves without magic.api
            [magic.spells.lift-vars :refer [lift-vars]]
            [magic.spells.lift-keywords :refer [lift-keywords]]
            [magic.spells.sparse-case :refer [sparse-case]]))

(deftest active-spells-follows-flags
  (testing "defaults: lift-vars then lift-keywords, in order"
    (is (= [#'lift-vars #'lift-keywords] (magic/active-spells))))
  (testing "each spell flag toggles its own spell independently"
    (is (= [#'lift-keywords]
           (binding [flags/*lift-vars* false] (magic/active-spells))))
    (is (= [#'lift-vars]
           (binding [flags/*lift-keywords* false] (magic/active-spells)))))
  (testing "sparse-case is off by default, enabled by its flag, and ordered last"
    (is (= [#'lift-vars #'lift-keywords #'sparse-case]
           (binding [flags/*sparse-case* true] (magic/active-spells)))))
  (testing "all spell flags off yields no spells"
    (is (empty? (binding [flags/*lift-vars* false
                          flags/*lift-keywords* false]
                  (magic/active-spells))))))
