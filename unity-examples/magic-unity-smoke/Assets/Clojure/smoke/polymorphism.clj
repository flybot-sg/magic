(ns smoke.polymorphism
  "Polymorphism mechanisms: protocols, reify, deftype, defrecord,
  and multimethods. Exercises protocol dispatch, generic sharing,
  and the protocol method-cache under IL2CPP. The mutable-field
  set! check is bound to the deftype set! castclass fix: stfld of
  an invoke return into a type-hinted mutable field used to emit
  without castclass, unverifiable IL that Mono accepts and IL2CPP
  rejects at C++ compile time. The protocol-hinted parameter check
  is bound to the hint-relax fix: a ^Protocol param used to cast to
  the protocol's generated interface, which extend-protocol
  implementers are not instances of. The IPersistentMap deftype is the
  only shape here that emits two methods differing solely in return
  type, which no C# source can express. The rest of the suite is broad
  construct coverage to catch dispatch-related regressions."
  (:require [smoke.check :refer [check]]))

(defprotocol IShape
  (area [s])
  (label [s]))

(defrecord Square [side]
  IShape
  (area  [_] (* side side))
  (label [_] "square"))

(defrecord Empty [])

(deftype Box [^long w ^long h]
  IShape
  (area  [_] (* w h))
  (label [_] "box"))

(defprotocol IAccum
  (add-item [a x])
  (item-vec [a]))

(deftype Accum [^:unsynchronized-mutable ^clojure.lang.PersistentVector items]
  IAccum
  (add-item [this x] (set! items (conj items x)) this)
  (item-vec [_] items))

(deftype MapBox [m]
  clojure.lang.IPersistentMap
  (count [_] (count m))
  (^clojure.lang.IPersistentCollection cons [_ e] (MapBox. (conj m e)))
  (^clojure.lang.IPersistentMap assoc [_ k v] (MapBox. (assoc m k v)))
  (^clojure.lang.Associative assoc [_ k v] (MapBox. (assoc m k v)))
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf))
  (seq [_] (seq m)))

(defprotocol IStrLen
  (str-len [s]))

(extend-protocol IStrLen
  System.String
  (str-len [s] (.Length s)))

(defn- hinted-len [^IStrLen s] (str-len s))

(defmulti animal-sound :kind)
(defmethod animal-sound :dog  [_] "woof")
(defmethod animal-sound :cat  [_] "meow")
(defmethod animal-sound :default [_] "shrug")

(defn suite []
  [(check "protocol on defrecord (area)"
          #(area (->Square 4)) 16)
   (check "protocol on defrecord (label)"
          #(label (->Square 4)) "square")
   (check "defrecord equiv true"
          #(= (->Square 4) (->Square 4)) true)
   (check "defrecord equiv false"
          #(= (->Square 4) (->Square 5)) false)
   (check "defrecord count"
          #(count (->Square 4)) 1)
   (check "defrecord conj + lookup"
          #(:b (conj (->Square 4) {:b 2})) 2)
   (check "empty defrecord equiv true"
          #(= (->Empty) (->Empty)) true)
   (check "empty defrecord count"
          #(count (->Empty)) 0)
   (check "empty defrecord seq is nil"
          #(seq (->Empty)) nil)
   (check "empty defrecord assoc + lookup"
          #(:b (assoc (->Empty) :b 2)) 2)
   (check "protocol on deftype (area)"
          #(area (Box. 3 5)) 15)
   (check "reify implementing protocol"
          #(area (reify IShape (area [_] 42) (label [_] "reified")))
          42)
   (check "reify implementing System.Object"
          #(let [o (reify System.Object
                     (Equals [_ x] (= x :sentinel))
                     (GetHashCode [_] 7))]
             [(.Equals o :sentinel) (.Equals o :other) (.GetHashCode o)])
          [true false 7])
   (check "proxy of System.Object"
          #(let [o (proxy [System.Object] []
                     (ToString [] "i-am-proxy"))]
             (.ToString o))
          "i-am-proxy")
   (check "proxy-super reaches base method"
          #(let [w (proxy [System.IO.StringWriter] []
                     (ToString [] (str "<" (proxy-super ToString) ">")))]
             (.Write w "hi")
             (.ToString w))
          "<hi>")
   (check "proxy-super with shadowed type-hinted this"
          #(let [w (proxy [System.IO.StringWriter] []
                     (ToString [] (let [^System.IO.StringWriter this this]
                                    (str "<" (proxy-super ToString) ">"))))]
             (.Write w "hi")
             (.ToString w))
          "<hi>")
   (check "deftype set! hinted mutable field from invoke return"
          #(item-vec (-> (->Accum []) (add-item 1) (add-item 2)))
          [1 2])
   (check "protocol-hinted param with extend-protocol implementer"
          #(hinted-len "hello")
          5)
   (check "deftype over IPersistentMap counts through the Counted slot"
          #(count (MapBox. {:a 1 :b 2})) 2)
   (check "return-type-hinted cons overload"
          #(count (conj (MapBox. {:a 1}) [:b 2])) 2)
   (check "return-type-hinted assoc overload"
          #(get (assoc (MapBox. {:a 1}) :b 2) :b) 2)
   (check "multimethod :dog"
          #(animal-sound {:kind :dog}) "woof")
   (check "multimethod default"
          #(animal-sound {:kind :unknown}) "shrug")])
