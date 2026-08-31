(ns smoke.numeric-casts
  "Narrowing conversions under AOT, one check per compiled shape.

  The project compiles under nostrand's production flags, which bind
  *unchecked-math* true, so a narrowing cast keeps its plain conv opcode
  and wraps. These pin that emission through IL2CPP. Compile with
  *unchecked-math* false and the same forms throw ArgumentException
  instead, which is what the compiler suite covers."
  (:require [smoke.check :refer [check]]))

(defn suite []
  [(check "out-of-range cast wraps"
          #((fn [^long x] (int x)) 4294967296) 0)
   (check "in-range cast converts"
          #((fn [^long x] (int x)) 7) 7)
   (check "value-preserving conversion keeps plain conv"
          #((fn [^int x] (long x)) 7) 7)
   (check "unchecked cast calls the RT unchecked cast"
          #((fn [^long x] (unchecked-int x)) 4294967296) 0)
   (check "aget index wraps to the low word"
          #(let [a (int-array [10 20 30])]
             ((fn [^long i] (aget a i)) 4294967296)) 10)
   (check "aset value wraps before the store"
          #(let [a (int-array [10 20 30])]
             ((fn [^long v] (aset a 0 v)) 4294967296)
             (aget a 0)) 0)
   (check "interop parameter wraps"
          #((fn [^long i] (.Substring "hello" i)) 4294967296) "hello")])
