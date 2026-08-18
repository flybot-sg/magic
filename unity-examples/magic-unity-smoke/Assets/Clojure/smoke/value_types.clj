(ns smoke.value-types
  "Instance methods on value types.

  Regression for develop 40b4237b: zero-arity instance members on
  Int64, Double, and other value types used to throw
  InvalidProgramException under IL2CPP because MAGIC emitted plain
  callvirt instead of constrained.callvirt."
  (:require [smoke.check :refer [check]]))

(defn suite []
  [(check "string .Length"      #(.Length "hello")              5)
   (check "long .GetType"       #(.GetType 90)                  System.Int64)
   (check "double .GetType"     #(.GetType 90.0)                System.Double)
   (check "string .GetType"     #(.GetType "hi")                System.String)
   (check "long .ToString"      #(.ToString 42)                 "42")
   (check "double .ToString"    #(.ToString 1.5)                "1.5")
   (check "long .Equals same"   #(.Equals 7 7)                  true)
   (check "long .Equals diff"   #(.Equals 7 8)                  false)
   (check "double .Equals same" #(.Equals 1.0 1.0)              true)])
