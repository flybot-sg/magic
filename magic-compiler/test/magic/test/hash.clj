(ns magic.test.hash
  (:require [clojure.test :refer [deftest is]]))

;; Ground-truth values from JVM Clojure 1.12. See nasser/magic#239.
(deftest hasheq-string-matches-jvm
  (doseq [[s jvm-hash] [[""                     0]
                        ["foo"                  493551392]
                        ["hello world"          -1522757468]
                        ["unicode-aware: π α λ" 397160936]
                        ["newline\nand\ttab"    -1044706529]]]
    (is (= jvm-hash (hash s)) (str "hash drift for " (pr-str s)))))

(defrecord Card [suit num])

;; Record-hash is JVM-equivalent: APersistentMap.mapHasheq delegates to
;; Murmur3.HashUnordered (JVM Clojure 1.6+). Ground-truth captured from
;; JVM with the same defrecord in the same ns.
(deftest hasheq-record-matches-jvm
  (doseq [[suit num jvm-hash] [[:s 3  916843116]
                               [:s 4  157183882]
                               [:h 3 1980878469]
                               [:h 4  165635771]]]
    (is (= jvm-hash (hash (->Card suit num)))
        (str "hash drift for record {:suit " suit " :num " num "}"))))

(deftest set-of-records-iteration-matches-jvm
  (let [iter (mapv (juxt :suit :num)
                   (set [(->Card :s 3) (->Card :s 4)
                         (->Card :h 3) (->Card :h 4)]))]
    (is (= [[:h 3] [:s 4] [:s 3] [:h 4]] iter)
        "set iteration order over records diverges from JVM")))
