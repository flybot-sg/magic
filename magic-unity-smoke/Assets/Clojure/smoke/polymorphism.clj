(ns smoke.polymorphism
  "Polymorphism mechanisms: protocols, reify, deftype, defrecord,
  and multimethods. Exercises protocol dispatch, generic sharing,
  and the protocol method-cache under IL2CPP. No develop-branch
  fix is bound specifically to one of these checks; the suite is
  broad construct coverage to catch dispatch-related regressions.")

(defn- pass [n]       {:name n :pass? true})
(defn- fail [n detail] {:name n :pass? false :detail detail})

(defn- check [name thunk expected]
  (try
    (let [actual (thunk)]
      (if (= expected actual)
        (pass name)
        (fail name (str "expected " (pr-str expected) " got " (pr-str actual)))))
    (catch System.Exception e
      (fail name (str (.. e GetType FullName) ": " (.Message e))))))

(defprotocol IShape
  (area [s])
  (label [s]))

(defrecord Square [side]
  IShape
  (area  [_] (* side side))
  (label [_] "square"))

(deftype Box [^long w ^long h]
  IShape
  (area  [_] (* w h))
  (label [_] "box"))

(defmulti animal-sound :kind)
(defmethod animal-sound :dog  [_] "woof")
(defmethod animal-sound :cat  [_] "meow")
(defmethod animal-sound :default [_] "shrug")

(defn suite []
  [(check "protocol on defrecord (area)"
          #(area (->Square 4)) 16)
   (check "protocol on defrecord (label)"
          #(label (->Square 4)) "square")
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
   (check "multimethod :dog"
          #(animal-sound {:kind :dog}) "woof")
   (check "multimethod default"
          #(animal-sound {:kind :unknown}) "shrug")])
