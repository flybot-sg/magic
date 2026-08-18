(ns smoke.intrinsics
  "Intrinsic lowering semantics under AOT, one check per compiled shape.

  A declined intrinsic keeps its inlined static call instead of falling
  back to a Var invoke; the nth intrinsic only covers the 2-arg form so
  the not-found arity keeps its bounds check; inc promotes at the Int32
  edge while unchecked-inc keeps the operand type and wraps."
  (:require [smoke.check :refer [check]]))

(defn suite []
  [(check "typed 2-arg nth, intrinsic ldelem"
          #((fn [^|System.Int32[]| a] (nth a 0)) (int-array [7 8])) 7)
   (check "typed 3-arg nth keeps the bounds check"
          #((fn [^|System.Int32[]| a] (nth a 5 :d)) (int-array 3)) :d)
   (check "untyped nth stays a static RT.nth call"
          #((fn [v] (nth v 1)) [:a :b]) :b)
   (check "untyped n-ary + stays nested static calls"
          #((fn [a b c] (+ a b c)) 1 2 3) 6)
   (check "untyped = stays a static Util.equiv call"
          #((fn [a b] (= a b)) "xy" (str "x" "y")) true)
   (check "inc promotes at the Int32 edge"
          #(inc Int32/MaxValue) 2147483648)
   (check "unchecked-inc keeps Int32 and wraps"
          #(unchecked-inc Int32/MaxValue) Int32/MinValue)])
