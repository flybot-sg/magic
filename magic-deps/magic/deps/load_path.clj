(ns magic.deps.load-path
  "Installing a project's source dirs and magic.edn :dev profile into MAGIC,
  for a host without nos. ClojureCLR lacks *load-paths*, so this namespace
  loads only on MAGIC."
  (:import [System.IO Directory Path])
  (:require [clojure.string :as str]
            [magic.deps.basis :as basis]
            [magic.deps.magic-edn :as magic-edn]))

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

(defn install-project-roots!
  "Put the existing source dirs of root's basis ahead of *load-paths*, for a
  host without nos. Returns {:roots installed :missing skipped}."
  [root]
  (let [paths   (into [] (comp (map absolute-path) (distinct))
                      (:classpath-paths (basis/project-basis root)))
        exists? #(Directory/Exists %)
        roots   (filterv exists? paths)]
    (alter-var-root #'*load-paths* #(prepend-load-paths roots %))
    {:roots   roots
     :missing (into [] (remove exists?) paths)}))

(defn install-dev-profile!
  [root]
  (let [config  (magic-edn/read-config root)
        options (magic-edn/options config :dev)
        dir     (absolute-path (Path/Combine (str root) (str (:out options))))
        exists? (Directory/Exists dir)]
    (magic-edn/set-flag-roots! (:flags options))
    (when exists?
      (alter-var-root #'*load-paths* #(prepend-load-paths [dir] %)))
    {:out    dir
     :status (cond exists?                    :installed
                   (get-in config [:dev :out]) :missing
                   :else                       :skipped)}))
