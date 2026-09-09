(ns magic.intrinsics
  (:require [mage.core :as il]
            [magic.core :as magic]
            [magic.analyzer :as ana]
            [magic.analyzer.literal-reinterpretation :refer [reinterpret-value reinterpret]]
            [magic.analyzer.intrinsics :as intrinsics :refer [register-intrinsic-form
                                                              register-intrinsic-method]]
            [magic.analyzer.types :as types :refer [tag ast-type non-void-ast-type]]
            [magic.interop :as interop]
            [clojure.string :as string])
  (:import [clojure.lang RT BigInteger Numbers Ratio Util]))

(defmacro defintrinsic
  "Register an intrinsic under a var's name, and optionally under the static
  methods its :inline expands to, given as [Type method-name arg-count].

  Both are needed because a call reaches the pass in one of two shapes:

  - inlinable arity: already expanded into a static method call, only a
    method registration can catch it
  - other arities: still a plain invocation of the var

  (defintrinsic clojure.core/+
    add-mul-numeric-type                    ; when do the types qualify
    (add-mul-numeric-compiler               ; the IL to emit when they do
      (il/ldc-i8 0) (il/add-ovf) (il/add))
    [Numbers \"add\" 2])                    ; (+ a b) inlines to Numbers.add"
  [name type-fn il-fn & lowerings]
  `(let [type-fn# ~type-fn
         il-fn# ~il-fn]
     (register-intrinsic-form '~name type-fn# il-fn#)
     (doseq [[t# m# c#] ~(vec lowerings)]
       (register-intrinsic-method t# m# c# type-fn# il-fn#))))

(defn promote-integer
  "Clojure's numeric tower computes all integer arithmetic in long, but
  MAGIC computes in the operand's own type, where the signed .ovf opcodes
  overflow or wrap at the edge of a narrow type. Widen each type so math
  matches the tower:

    Byte SByte Int16 UInt16 Int32 UInt32  ->  Int64 (fits losslessly)
    Int64                                 ->  Int64 (already long)
    UInt64                                ->  unchanged: cannot fit Int64,
                                              ClojureCLR uses BigInteger

  Without the promotion (inc Int32/MaxValue) throws and
  (inc UInt32/MaxValue) wraps to 0."
  [t]
  (if (#{Byte SByte Int16 UInt16 Int32 UInt32} t) Int64 t))

(defn numeric-args [{:keys [args]}]
  (let [arg-types (->> args (map ast-type))
        non-numeric-args (filter (complement types/numeric) arg-types)]
    (when (empty non-numeric-args)
      (promote-integer (types/best-numeric-promotion arg-types)))))

(defn best-numeric-type [{:keys [args]}]
  (let [arg-types (->> args (map ast-type))
        non-numeric-args (filter (complement types/numeric) arg-types)
        inline? (empty? non-numeric-args)
        type (->> args (map ast-type) types/best-numeric-promotion promote-integer)]
    (when inline? type)))

(defn add-mul-numeric-type [{:keys [args] :as ast}]
  (if (empty? args)
    Int64
    (numeric-args ast)))

(defn numeric-arg [{:keys [args]}]
  (let [arg-type (-> args first ast-type)]
    (when (types/numeric arg-type)
      arg-type)))

(defn when-numeric-arg [{:keys [args]} x]
  (let [arg-type (-> args first ast-type)]
    (when (types/numeric arg-type)
      x)))

(defn add-mul-numeric-compiler [ident checked unchecked]
  (fn add-mul-compiler
    [{:keys [args] :as ast} type compilers]
    (if (zero? (count args))
      ident
      [(interleave
         (map #(magic/compile (reinterpret % type) compilers) args)
         (map #(magic/convert % type) args))
       (repeat (-> args count dec)
               (if (not (or *unchecked-math*
                            (= type Single)
                            (= type Double)))
                 checked
                 unchecked))])))

(defn conversion-compiler
  [{:keys [args]} type compilers]
  (let [arg (reinterpret (first args) type)]
    [(magic/compile arg compilers)
     (magic/convert arg type (not *unchecked-math*))]))

;; Vars without an :inline (sbyte, uint, ulong, ushort) need no method entry.
;; The unchecked casts stay unkeyed: conversion-compiler emits the checked
;; cast, which would turn their wrapping into throwing.
(def conversions
  {'clojure.core/float  [Single "floatCast"]
   'clojure.core/double [Double "doubleCast"]
   'clojure.core/char   [Char "charCast"]
   'clojure.core/byte   [Byte "byteCast"]
   'clojure.core/sbyte  [SByte]
   'clojure.core/int    [Int32 "intCast"]
   'clojure.core/uint   [UInt32]
   'clojure.core/long   [Int64 "longCast"]
   'clojure.core/ulong  [UInt64]
   'clojure.core/short  [Int16 "shortCast"]
   'clojure.core/ushort [UInt16]})

(reduce-kv
  (fn [_ sym [type & cast-methods]]
    (register-intrinsic-form sym (constantly type) conversion-compiler)
    (doseq [m cast-methods]
      (register-intrinsic-method RT m 1 (constantly type) conversion-compiler)))
  nil
  conversions)

(defintrinsic clojure.core/+
  add-mul-numeric-type
  (add-mul-numeric-compiler
    (il/ldc-i8 0) (il/add-ovf) (il/add))
  [Numbers "add" 2])

(register-intrinsic-method Numbers "unchecked_add" 2
  add-mul-numeric-type
  (add-mul-numeric-compiler
    (il/ldc-i8 0) (il/add) (il/add)))

(defn inc-dec-numeric-compiler [checked unchecked]
  (fn intrinsic-inc-dec-compiler
    [{:keys [args] :as ast} type compilers]
    (let [arg (first args)]
      [(magic/compile arg compilers)
       (magic/convert arg type)
       (magic/load-constant
         (reinterpret-value 1 type))
       (if (or *unchecked-math*
               (= type Single)
               (= type Double))
         unchecked
         checked)])))

(defintrinsic clojure.core/inc
  add-mul-numeric-type
  (inc-dec-numeric-compiler (il/add-ovf) (il/add))
  [Numbers "inc" 1])

(defn sub-numeric-compiler [checked unchecked]
  (fn intrinsics-sub-compiler
    [{:keys [args] :as ast} type compilers]
    (let [first-arg (first args)
          rest-args (rest args)
          instr (if (not (or *unchecked-math*
                             (= type Single)
                             (= type Double)))
                  checked
                  unchecked)]
      (if (empty? rest-args)
        [(magic/compile (reinterpret first-arg type) compilers)
         (magic/convert first-arg type)
         (il/neg)]
        [(magic/compile (reinterpret first-arg type) compilers)
         (magic/convert first-arg type)
         (mapcat
           (fn [a]
             [(magic/compile (reinterpret a type) compilers)
              (magic/convert a type)
              instr])
           rest-args)]))))

(defintrinsic clojure.core/-
  best-numeric-type
  (sub-numeric-compiler (il/sub-ovf) (il/sub))
  [Numbers "minus" 1]
  [Numbers "minus" 2])

(register-intrinsic-method Numbers "unchecked_minus" 1
  best-numeric-type
  (sub-numeric-compiler (il/sub) (il/sub)))

(register-intrinsic-method Numbers "unchecked_minus" 2
  best-numeric-type
  (sub-numeric-compiler (il/sub) (il/sub)))

(defintrinsic clojure.core/dec
  best-numeric-type
  (inc-dec-numeric-compiler (il/sub-ovf) (il/sub))
  [Numbers "dec" 1])

(register-intrinsic-method Numbers "unchecked_dec" 1
  numeric-arg
  (fn intrinsic-unchecked-dec-compiler
    [{:keys [args] :as ast} type compilers]
    [(magic/compile (first args) compilers)
     (il/ldc-i4-1)
     (magic/convert-type Int32 type)
     (il/sub)]))

(defintrinsic clojure.core/*
  add-mul-numeric-type
  (add-mul-numeric-compiler
    (il/ldc-i8 1) (il/mul-ovf) (il/mul))
  [Numbers "multiply" 2])

(register-intrinsic-method Numbers "unchecked_multiply" 2
  add-mul-numeric-type
  (add-mul-numeric-compiler
    (il/ldc-i8 1) (il/mul) (il/mul)))

(defintrinsic clojure.core//
  #(let [t (best-numeric-type %)]
     (when-not (types/integer t)
       t))
  (fn intrinsics-div-compiler
    [{:keys [args] :as ast} type compilers]
    (let [first-arg (first args)
          first-type (ast-type first-arg)
          rest-args (rest args)]
      (cond
        (and (empty? rest-args)
             (types/integer first-type))
        [(il/call (interop/getter BigInteger "One"))
         (magic/compile first-arg compilers)
         (magic/convert first-arg BigInteger)
         (il/newobj (interop/constructor Ratio BigInteger BigInteger))]
        (empty? rest-args)
        [(magic/load-constant
           (reinterpret-value 1 first-type))
         (magic/compile first-arg compilers)
         (il/div)]
        (= Ratio type)
        (let [second-arg (second args)
              rest-args (drop 2 args)]
          [(magic/compile first-arg compilers)
           (magic/convert first-arg BigInteger)
           (magic/compile second-arg compilers)
           (magic/convert second-arg BigInteger)
           (mapcat
             (fn [a]
               [(magic/compile a compilers)
                (magic/convert a BigInteger)
                (il/call (interop/method BigInteger "op_Multiply" BigInteger BigInteger))])
             rest-args)
           (il/call (interop/method Numbers "BIDivide" BigInteger BigInteger))
           (magic/convert-type Object Ratio)])
        :else
        [(magic/compile (reinterpret first-arg type) compilers)
         (magic/convert first-arg type)
         (mapcat
           (fn [a]
             [(magic/compile (reinterpret a type) compilers)
              (magic/convert a type)
              (il/div)])
           rest-args)])))
  [Numbers "divide" 2])

(defintrinsic clojure.core/<
  #(when (numeric-args %) Boolean)
  (fn intrinsic-lt-compiler
    [{:keys [args] :as ast} type compilers]
    (let [arg-pairs (partition 2 1 args)
          greater-label (il/label)
          true-label (il/label)
          end-label (il/label)
          il-pairs
          (map (fn [[a b]]
                 (let [best-numeric-type
                       (->> [a b] (map ast-type) types/best-numeric-promotion)]
                   [(magic/compile a compilers)
                    (magic/convert a best-numeric-type)
                    (magic/compile b compilers)
                    (magic/convert b best-numeric-type)]))
               arg-pairs)]
      [(->> (interleave il-pairs (repeat (il/bge greater-label)))
            drop-last)
       (il/clt)
       (when (> (count il-pairs) 1)
        [(il/br end-label)
         greater-label
         (il/ldc-i4-0)
         end-label])]))
  [Numbers "lt" 2])

(defintrinsic clojure.core/>
  #(when (numeric-args %) Boolean)
  (fn intrinsic-lt-compiler
    [{:keys [args] :as ast} type compilers]
    (let [arg-pairs (partition 2 1 args)
          less-label (il/label)
          true-label (il/label)
          end-label (il/label)
          il-pairs
          (map (fn [[a b]]
                 (let [best-numeric-type
                       (->> [a b] (map ast-type) types/best-numeric-promotion)]
                   [(magic/compile a compilers)
                    (magic/convert a best-numeric-type)
                    (magic/compile b compilers)
                    (magic/convert b best-numeric-type)]))
               arg-pairs)]
      [(->> (interleave il-pairs (repeat (il/ble less-label)))
            drop-last)
       (il/cgt)
       (when (> (count il-pairs) 1)
       [(il/br end-label)
        less-label
        (il/ldc-i4-0)
        end-label])]))
  [Numbers "gt" 2])

(defintrinsic clojure.core/=
  #(when (numeric-args %) Boolean)
  (fn intrinsic-eq-compiler
    [{:keys [args] :as ast} type compilers]
    (case (count args)
      1
      (il/ldc-i4-1)
      (let [arg-pairs (partition 2 1 args)
            not-equal-label (il/label)
            end-label (il/label)
            il-pairs
            (map (fn [[a b]]
                   (let [best-numeric-type
                         (->> [a b] (map ast-type) types/best-numeric-promotion)]
                     [(magic/compile a compilers)
                      (magic/convert a best-numeric-type)
                      (magic/compile b compilers)
                      (magic/convert b best-numeric-type)]))
                 arg-pairs)]
        [(->> (interleave il-pairs (repeat [(il/bne-un not-equal-label)]))
              drop-last)
         (il/ceq)
         (il/br end-label)
         not-equal-label
         (il/ldc-i4-0)
         end-label])))
  [Util "equiv" 2])

(defintrinsic clojure.core/not=
  #(when (numeric-args %) Boolean)
  (fn intrinsic-not-eq-compiler
    [{:keys [args] :as ast} type compilers]
    (case (count args)
      1
      (il/ldc-i4-0)
      (let [arg-pairs (partition 2 1 args)
            equal-label (il/label)
            end-label (il/label)
            il-pairs
            (map (fn [[a b]]
                   (let [best-numeric-type
                         (->> [a b] (map ast-type) types/best-numeric-promotion)]
                     [(magic/compile a compilers)
                      (magic/convert a best-numeric-type)
                      (magic/compile b compilers)
                      (magic/convert b best-numeric-type)]))
                 arg-pairs)]
        [(->> (interleave il-pairs (repeat [(mage.core/beq equal-label)]))
              drop-last)
         (il/ceq)
         (il/ldc-i4-0)
         (il/ceq)
         (il/br end-label)
         equal-label
         (il/ldc-i4-0)
         end-label]))))

(defintrinsic clojure.core/deref
  #(when (->> % :args first ast-type (.IsAssignableFrom clojure.lang.IDeref)) Object)
  (fn intrinsic-deref-compiler
    [{:keys [args] :as ast} type compilers]
    [(magic/compile (first args) compilers)
     (magic/convert (first args) clojure.lang.IDeref)
     (il/callvirt (interop/method clojure.lang.IDeref "deref"))
     ]))

(defn array-type [{:keys [args]}]
  (let [type (-> args first ast-type)]
    (when (types/is-array? type) type)))

(defn when-array-type [{:keys [args]} v]
  (let [type (-> args first ast-type)]
    (when (types/is-array? type) v)))

(defn array-element-type [{:keys [args]}]
  (let [type (-> args first ast-type)]
    (when (types/is-array? type)
      (.GetElementType type))))

(defintrinsic clojure.core/aclone
  array-type
  (fn intrinsic-aclone-compiler
    [{:keys [args] :as ast} type compilers]
    [(magic/compile (first args) compilers)
     (il/callvirt (interop/method type "Clone"))
     (magic/convert-type Object type)])
  [RT "aclone" 1])

;; TODO multidim arrays

(defintrinsic clojure.core/aget
  (fn [{:keys [args] :as ast}]
    (when (= (count args) 2)
      (array-element-type ast)))
  (fn intrinsic-aget-compiler
    [{:keys [args] :as ast} type compilers]
    (let [[array-arg index-arg] args
          index-arg (reinterpret index-arg Int32)]
      [(magic/compile array-arg compilers)
       (magic/compile index-arg compilers)
       (magic/convert-type (ast-type index-arg) Int32 (not *unchecked-math*))
       (magic/load-element type)]))
  [RT "aget" 2])

;; TODO multidim arrays

(defintrinsic clojure.core/aset
  (fn [{:keys [args] :as ast}] 
    (when (= (count args) 3)
      (when-let [array-type (array-element-type ast)]
        (if (magic/statement? ast)
          System.Void
          array-type))))
  (fn intrinsic-aset-compiler
    [{:keys [args] :as ast} type compilers]
    (let [[array-arg index-arg value-arg] args
          index-arg (reinterpret index-arg Int32)
          type (array-element-type ast)
          val-return (il/local type)
          statement? (magic/statement? ast)]
      [(magic/compile array-arg compilers)
       (magic/compile index-arg compilers)
       (magic/convert-type (ast-type index-arg) Int32 (not *unchecked-math*))
       (magic/compile value-arg compilers)
       (magic/convert-type (ast-type value-arg) type (not *unchecked-math*))
       (when-not statement?
         [(il/dup)
          (il/stloc val-return)])
       (magic/store-element type)
       (when-not statement?
         (il/ldloc val-return))]))
  [RT "aset" 3])

(defintrinsic clojure.core/nth
  (fn [{:keys [args] :as ast}]
    (when (= (count args) 2)
      (array-element-type ast)))
  (fn intrinsic-nth-compiler
    [{:keys [args] :as ast} type compilers]
    (let [[array-arg index-arg] args
          index-arg' (reinterpret index-arg Int32)
          value-type? (.IsValueType type)]
      [(magic/compile array-arg compilers)
       (if-not (= index-arg' index-arg)
         (magic/compile index-arg' compilers)
         [(magic/compile index-arg compilers)
          (magic/convert index-arg Int32 (not *unchecked-math*))])
       (if value-type?
         (il/ldelem type)
         (il/ldelem-ref))]))
  [RT "nth" 2])

(defintrinsic clojure.core/alength
  #(when-array-type % Int32)
  (fn intrinsic-alength-compiler
    [{:keys [args] :as ast} type compilers]
    [(magic/compile (first args) compilers)
     (il/ldlen)])
  [RT "alength" 1])

(defintrinsic clojure.core/unchecked-inc
  numeric-arg
  (fn intrinsic-unchecked-inc-compiler
    [{:keys [args] :as ast} type compilers]
    [(magic/compile (first args) compilers)
     (il/ldc-i4-1)
     (magic/convert-type Int32 type)
     (il/add)])
  [Numbers "unchecked_inc" 1])

(defintrinsic clojure.core/unchecked-inc-int
  #(when-numeric-arg % Int32)
  (fn intrinsic-unchecked-inc-int-compiler
    [{:keys [args] :as ast} type compilers]
    [(magic/compile (first args) compilers)
     (magic/convert (first args) Int32)
     (il/ldc-i4-1)
     (il/add)])
  [Numbers "unchecked_int_inc" 1])

(defintrinsic clojure.core/instance?
  (fn [{[first-arg] :args :keys [args]}]
    (and (= 2 (count args))
         (when (and (= :const (:op first-arg))
                    (= :class (:type first-arg)))
           Boolean)))
  (fn intrinsic-instance?-compiler
    [{[{:keys [val] :as type-arg} obj-arg] :args} type compilers]
    (let [obj-arg-type (ast-type obj-arg)]
      (if (and obj-arg-type
               (types/is-value-type? obj-arg-type))
        (if (= obj-arg-type val)
          (il/ldc-i4-1)
          (il/ldc-i4-0))
        [(magic/compile obj-arg compilers)
         (il/isinst val)
         (il/ldnull)
         (il/cgt-un)]))))

(defintrinsic clojure.core/count
  (constantly Int32)
  (fn intrinsic-count-compiler
    [{[first-arg] :args} type compilers]
    (let [arg-type (ast-type first-arg)]
      [(magic/compile first-arg compilers)
       (cond
         (types/is-array? arg-type)
         (il/ldlen)
         (= String arg-type)
         (il/callvirt (interop/method String "get_Length"))
         :else
         [(magic/convert first-arg Object)
          (il/call (interop/method RT "count" Object))])]))
  [RT "count" 1])

(defintrinsic clojure.core/make-array
  (fn [{[first-arg :as args] :args}]
    (when (= (count args) 2)
     (when (and (= :const (:op first-arg))
                (= :class (:type first-arg)))
       (.MakeArrayType (:val first-arg)))))
  (fn intrinsic-make-array-compiler
    [{[type-arg len-arg] :args} type compilers]
    [(magic/compile len-arg compilers)
     (magic/convert len-arg Int32 (not *unchecked-math*))
     (il/newarr (:val type-arg))]))

(defintrinsic clojure.core/enum-or
  (fn [{:keys [args]}]
    (let [arg-set (->> args (map ast-type) (into #{}))
          t (first arg-set)]
      (when (and (= 1 (count arg-set))
                 (.IsEnum t))
        t)))
  (fn intrinsic-enum-or-compiler
    [{:keys [args]} type compilers]
    [(magic/compile (first args) compilers)
     (interleave
      (map #(magic/compile % compilers) (drop 1 args))
      (repeat (il/or)))]))

(defintrinsic clojure.core/not
  (constantly Boolean)
  (fn intrinsic-not-compiler
    [{:keys [args] :as ast} type compilers]
    (let [arg (first args)]
      [(magic/compile arg compilers)
       (magic/convert arg Boolean)
       (il/ldc-i4-0)
       (il/ceq)])))

(defintrinsic clojure.core/neg?
  #(when-numeric-arg % Boolean)
  (fn intrinsic-neg?-compiler
    [{:keys [args] :as ast} type compilers]
    (let [arg (first args)
          arg-type (types/ast-type arg)]
      [(magic/compile arg compilers)
       (cond
         (= arg-type SByte)  [(il/ldc-i4-0) (il/clt)]
         (= arg-type Byte)   [(il/ldc-i4-0) (il/clt)]
         (= arg-type Int16)  [(il/ldc-i4-0) (il/clt)]
         (= arg-type UInt16) [(il/ldc-i4-0) (il/clt)]
         (= arg-type Int32)  [(il/ldc-i4-0) (il/clt)]
         (= arg-type UInt32) [(il/ldc-i4-0) (il/clt-un)]
         (= arg-type Int64)  [(il/ldc-i4-0) (il/conv-i8) (il/clt)]
         (= arg-type UInt64) [(il/ldc-i4-0) (il/conv-i8) (il/clt-un)]
         (= arg-type Single) [(il/ldc-r4 (float 0)) (il/clt)]
         (= arg-type Double) [(il/ldc-r8 0.0) (il/clt)]
         :else (throw (ex-info "intrinsic neg? failed, unexpected type" {:type arg-type})))]))
  [Numbers "isNeg" 1])

;;;; array functions
;; amap
;; areduce
;; aset-*
;; *-array
;; into-array
;; nth
;; to-array
;; to-array-2d