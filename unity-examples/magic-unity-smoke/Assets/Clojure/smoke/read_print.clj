(ns smoke.read-print
  "Reading and printing floating point.

  Regression for two Mono divergences from the JVM and .NET Core, fixed in the
  reader and the printer rather than worked around.

  Reading: Double.Parse throws OverflowException on an out-of-range literal
  where Double.parseDouble saturates, so MatchNumber now catches it and returns
  an infinity. Under AOT this covers exception handling inside the reader.

  Printing: the default ToString emits 15 significant digits for a double and 7
  for a float, so neither read back equal. fp-str now uses the \"R\" round-trip
  specifier, and IL2CPP supplies its own
  Double.ToString(string, IFormatProvider), so the Mono result does not cover
  this.

  One check per distinct branch: each sign of the infinity ternary, each of the
  two reader copies, the two ways Parse can overflow, the two cases that must
  still NOT saturate, both numeric widths through fp-str, and the symbolic
  values that bypass fp-str entirely."
  (:require [smoke.check :refer [check]]
            [clojure.edn :as edn]))

(def ^:private ic System.Globalization.CultureInfo/InvariantCulture)

(defn suite []
  [;; the literal is read at compile time, so this pins the emitted constant
   (check "out-of-range literal compiles to Inf"
          #(identity 1E1000)
          Double/PositiveInfinity)
   ;; both signs of the saturation ternary, through the runtime reader
   (check "read-string of out-of-range literal"
          #(read-string "1E1000")
          Double/PositiveInfinity)
   (check "read-string of negative out-of-range literal"
          #(read-string "-1E1000")
          Double/NegativeInfinity)
   ;; EdnReader carries its own copy of MatchNumber
   (check "edn/read-string of out-of-range literal"
          #(edn/read-string "1E1000")
          Double/PositiveInfinity)
   ;; overflow via an exponent that does not fit Int32, a different internal path
   (check "exponent too large for Int32 still reads as Inf"
          #(read-string "1E99999999999999999999")
          Double/PositiveInfinity)
   ;; must NOT saturate: underflow returns zero, malformed input still errors
   (check "underflow reads as zero, not Inf"
          #(read-string "1E-1000")
          0.0)
   (check "malformed literal still fails to read"
          #(try (pr-str (read-string "1.2.3"))
                (catch System.FormatException _ :threw))
          :threw)
   ;; fp-str at both widths; the default format loses 1125899906842624 and
   ;; 0.333333343, so these fail without the "R" specifier
   (check "double survives pr-str then read-string"
          #(let [d (Math/Pow 2 50)] (= d (read-string (pr-str d))))
          true)
   (check "float survives pr-str then read-string"
          #(let [f (float (/ 1.0 3.0))] (= f (System.Single/Parse (pr-str f) ic)))
          true)
   ;; fp-str's other branch: append ".0" when "R" yields no "." or "E"
   (check "ordinary doubles print unchanged"
          #(mapv pr-str [0.1 1.0 0.5 100.25])
          ["0.1" "1.0" "0.5" "100.25"])
   ;; symbolic values short-circuit before fp-str, and must survive a round-trip
   (check "infinities and NaN print as symbolic values"
          #(mapv pr-str [Double/PositiveInfinity Double/NegativeInfinity Double/NaN])
          ["##Inf" "##-Inf" "##NaN"])
   (check "symbolic infinity survives pr-str then read-string"
          #(read-string (pr-str Double/PositiveInfinity))
          Double/PositiveInfinity)])
