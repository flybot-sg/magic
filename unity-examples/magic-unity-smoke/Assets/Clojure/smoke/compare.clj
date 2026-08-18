(ns smoke.compare
  "Default comparison semantics: strings, symbols, and keywords compare
  by UTF-16 code unit, matching the JVM, never the OS collation rules.
  The comparison lives in Clojure.dll's C#, which IL2CPP transpiles like
  everything else, so the ordering needs the AOT gate too."
  (:require [smoke.check :refer [check]]))

(defn suite []
  [(check "string sort is by code unit"
          #(sort ["a" "B" "b" "A"])
          ["A" "B" "a" "b"])
   (check "accented string sorts after plain letters"
          #(sort ["e" "é" "f"])
          ["e" "f" "é"])
   (check "compare sign on strings matches JVM"
          #(pos? (compare "a" "B"))
          true)
   (check "non-interned equal strings compare to zero"
          #(compare "ab" (str "a" "b"))
          0)
   (check "symbol sort is by code unit"
          #(sort '[a B b A])
          '[A B a b])
   (check "qualified symbol namespace compares by code unit"
          #(sort '[x/a X/a x/B])
          '[X/a x/B x/a])
   (check "keyword sort is by code unit"
          #(sort [:a :B :b :A])
          [:A :B :a :b])
   (check "sorted-set orders by code unit"
          #(seq (sorted-set "a" "B" "b" "A"))
          ["A" "B" "a" "b"])])
