(ns magic.test.deftype
  (:require [clojure.test :refer [deftest is]]
            [magic.api :as m]))

(deftest inherited-slot-covered-by-one-method
  ;; Counted, IPersistentCollection and IPersistentMap each declare int count().
  ;; RT.count calls the Counted slot, so one written count has to fill all three.
  (is (= 2 (m/eval '(do (deftype CountBox [m]
                          clojure.lang.IPersistentMap
                          (count [_] (count m))
                          (seq [_] (seq m)))
                        (count (CountBox. {:a 1 :b 2})))))))

(deftest return-type-hint-selects-overload
  (is (= [true true 1]
         (m/eval '(do (deftype ConsBox [m]
                        clojure.lang.IPersistentMap
                        (count [_] (count m))
                        (^clojure.lang.IPersistentCollection cons [this _] this)
                        (^clojure.lang.IPersistentMap assoc [this _ _] this)
                        (^clojure.lang.Associative assoc [this _ _] this)
                        (seq [_] (seq m)))
                      (let [b (ConsBox. {:a 1})]
                        [(identical? b (conj b [:c 3]))
                         (identical? b (assoc b :c 3))
                         (count b)]))))))

(deftest unwritten-slot-emits-one-default
  (is (= 1 (m/eval '(do (deftype PlainBox [m]
                          clojure.lang.IPersistentMap
                          (seq [_] (seq m)))
                        (->> (.GetMethods PlainBox (enum-or System.Reflection.BindingFlags/Instance
                                                            System.Reflection.BindingFlags/Public
                                                            System.Reflection.BindingFlags/DeclaredOnly))
                             (filter #(= "count" (.Name %)))
                             count))))))

(deftest unhinted-overload-names-the-return-types
  (is (thrown-with-msg?
       clojure.lang.ClojureException #"Overloaded on return type"
       (m/eval '(deftype AmbiguousBox [m]
                  clojure.lang.IPersistentMap
                  (cons [this _] this)
                  (seq [_] (seq m)))))))

(deftest interface-qualified-name-binds-its-own-slot
  ;; The form defrecord expands to.
  (is (= [2 1] (m/eval '(do (defrecord Pair [a b])
                            (let [p (->Pair 1 2)]
                              [(count p) (:a p)]))))))

(deftest overloads-outside-clojure-lang
  ;; IDictionary.GetEnumerator and IEnumerable.GetEnumerator are two slots; the
  ;; BCL collection interfaces carry more of these than clojure.lang does.
  ;; Two written overloads stay two methods on the emitted type; the merging
  ;; that count gets must not reach a name whose return types differ.
  (is (= [2 true 2]
         (m/eval '(do (deftype Dict [m]
                        System.Collections.IDictionary
                        (^System.Collections.IDictionaryEnumerator GetEnumerator [_] nil)
                        (^System.Collections.IEnumerator GetEnumerator [_] nil)
                        (get_Count [_] (count m))
                        (Contains [_ k] (contains? m k)))
                      (let [d (Dict. {:a 1 :b 2})]
                        [(.Count ^System.Collections.ICollection d)
                         (.Contains d :a)
                         (->> (.GetMethods Dict (enum-or System.Reflection.BindingFlags/Instance
                                                         System.Reflection.BindingFlags/Public
                                                         System.Reflection.BindingFlags/DeclaredOnly))
                              (filter #(= "GetEnumerator" (.Name %)))
                              count)]))))))

(deftest reify-covers-the-slot-too
  (is (= 2 (m/eval '(count (reify clojure.lang.IPersistentMap
                             (count [_] 2)
                             (seq [_] nil)))))))

(deftest proxy-covers-the-slot-too
  ;; proxy binds its methods through the same pass but omits this from the
  ;; params, and its defaults are split across a super map and an interface one.
  (is (= [7 7] (m/eval '(let [p (proxy [clojure.lang.IPersistentMap] []
                                  (count [] 7)
                                  (seq [] nil))]
                          [(count p) (.count ^clojure.lang.Counted p)])))))

(deftest unknown-method-reports-a-plain-miss
  ;; A name no interface declares is not an overload, so it keeps the generic
  ;; message rather than the return type hint one.
  (is (thrown-with-msg?
       clojure.lang.ClojureException #"No match binding method"
       (m/eval '(deftype Absent []
                  clojure.lang.Counted
                  (nonexistent [_] 1))))))
