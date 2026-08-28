(ns smoke.csharp
  "Calls into a C# assembly a library ships, which IL2CPP has to keep callable
  like any other interop."
  (:require [smoke.check :refer [check]]
            [smoke-csharp.load-dll])
  (:import [smoke_csharp Greeter]))

(defn suite []
  [(check "a shipped assembly's static method is callable"
          #(Greeter/Greet "magic")
          "hello, magic")
   (check "a shipped assembly's value-typed args stay unboxed"
          #(Greeter/Add 2 3)
          5)
   (check "a shipped assembly's const field reads"
          (fn [] Greeter/Marker)
          "smoke-csharp-v1")])
