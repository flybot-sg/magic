(ns fixture.a
  (:require [fixture.b :as b]))

(defn call-b [] (b/value))
