(ns
 ^{:doc "The `nos` command line. Options and source files go to clojure.main
  unchanged; anything else names a task, run as `clojure -X` runs one."}
 nostrand.main
  (:require clojure.main
            nostrand.exec))

(def ^:private source-extensions [".clj" ".cljc" ".cljr"])

(defn- clojure-main-arg?
  [^String arg]
  (or (.StartsWith arg "-")
      (.StartsWith arg "@")
      (boolean (some #(.EndsWith arg ^String %) source-extensions))))

(defn main
  [& args]
  (if (clojure-main-arg? (first args))
    (apply clojure.main/main args)
    (apply clojure.main/main "-m" "nostrand.exec" args)))
