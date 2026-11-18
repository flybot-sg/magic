(ns magic.deps.load-path
  "Putting a basis's source dirs on MAGIC's *load-paths*, which ClojureCLR
  lacks, so this namespace loads only on MAGIC."
  (:import [System.IO Path])
  (:require [clojure.string :as str]))

(defn absolute-path
  "path resolved against the current directory, without a trailing separator,
  so equal dirs compare equal as load-path entries."
  [path]
  (when (str/blank? (str path))
    (throw (ex-info "Load path entry is blank" {:path path})))
  (let [full (Path/GetFullPath (str path))
        sep  (str Path/DirectorySeparatorChar)]
    (if (and (> (count full) 1) (str/ends-with? full sep))
      (subs full 0 (dec (count full)))
      full)))

(defn prepend-load-paths
  "load-paths with roots first, each root once."
  [roots load-paths]
  (into (vec roots) (remove (set roots)) load-paths))
