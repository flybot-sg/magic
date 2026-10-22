(ns magic.util
  (:refer-clojure :exclude [gensym]))

(defn ordinal-str-compare
  "Compare by raw UTF-16 units: CLR String.CompareTo (what compare uses)
   is culture-sensitive, so sorting with it varies across machines."
  [^String a ^String b]
  (String/CompareOrdinal a b))

(defonce ^:dynamic *gensym-map* (atom {}))

(defn gensym [base]
  (let [base (str base)]
    (str base "_"
         (get (swap! *gensym-map* update base #(if % (inc %) 0))
              base))))

(defmacro reset-gensym [keys & body]
  `(binding [*gensym-map* (atom (dissoc @*gensym-map* ~keys))]
     ~@body))