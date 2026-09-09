(ns smoke.numeric-casts
  "Narrowing conversions under AOT, one check per compiled shape.

  A cast that cannot hold its value calls the RT cast and throws, an
  in-range one returns, a value-preserving one keeps its plain conv
  opcode, and unchecked-int keeps calling the RT unchecked cast. The
  array index, the aset value and an interop parameter each narrow at
  their own call site."
  (:require [smoke.check :refer [check]]))

(defn- threw? [thunk]
  (try (thunk) :no-throw (catch ArgumentException e :threw)))

(defn suite []
  [(check "out-of-range cast throws"
          #(threw? (fn [] ((fn [^long x] (int x)) 4294967296))) :threw)
   (check "in-range cast converts"
          #((fn [^long x] (int x)) 7) 7)
   (check "value-preserving conversion keeps plain conv"
          #((fn [^int x] (long x)) 7) 7)
   (check "unchecked cast calls the RT unchecked cast"
          #((fn [^long x] (unchecked-int x)) 4294967296) 0)
   (check "aget index narrowing throws"
          #(threw? (fn [] (let [a (int-array [10 20 30])]
                            ((fn [^long i] (aget a i)) 4294967296)))) :threw)
   (check "aset value narrowing throws"
          #(threw? (fn [] (let [a (int-array [10 20 30])]
                            ((fn [^long v] (aset a 0 v)) 4294967296)))) :threw)
   (check "interop parameter narrowing throws"
          #(threw? (fn [] ((fn [^long i] (.Substring "hello" i)) 4294967296))) :threw)
   (check "boxed ulong casts through longCast"
          #(int (identity (ulong 1))) 1)
   (check "fractional literal cast truncates"
          #(int 1.5) 1)
   (check "out-of-range literal cast throws at runtime"
          #(threw? (fn [] (int 4294967296))) :threw)])
