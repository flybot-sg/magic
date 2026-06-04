(ns nostrand.deps.basis
  (:import [System.IO File])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nostrand.deps.git :as git]))

(def ^:private runtime-provided
  "Libs that ship inside Clojure.dll, so they are never resolved."
  '#{org.clojure/clojure
     org.clojure/core.specs.alpha
     org.clojure/spec.alpha})

(defn- runtime-provided? [lib] (contains? runtime-provided lib))

(defn- native-coord?
  "True when a coord can be procured natively: a git clone or a local path.
  Maven coords are not resolved here; they are skipped (see resolve-deps).
  JVM-only test tooling (kaocha, cider, ...) lives under aliases as :mvn
  coords, so this lets a :test alias contribute its :extra-paths without its
  tooling tripping resolution."
  [{:keys [git/url local/root]}]
  (boolean (or url root)))

(defn- merge-aliases
  "Fold the selected aliases into {:paths :deps :overrides}. :extra-paths
  append to :paths; :extra-deps merge onto the dep set; :override-deps are
  kept separate (an override swaps a lib's coord wherever it is encountered
  in the tree, without itself seeding a root dependency)."
  [{:keys [paths deps aliases]} alias-kws]
  (let [selected (map aliases alias-kws)]
    {:paths     (into (vec (or paths ["src"])) (mapcat :extra-paths selected))
     :deps      (apply merge deps (map :extra-deps selected))
     :overrides (apply merge (map :override-deps selected))}))

(defn- lib-paths
  "Absolute source paths a resolved lib contributes, rooted at its checkout
  dir. Preference: an explicit :paths on the coord (for git/local deps whose
  repo has no deps.edn or a non-src layout, e.g. a pom-only contrib lib under
  src/main/clojure), else the lib's own deps.edn :paths, else [\"src\"]."
  [dir coord lib-deps-edn]
  (map #(str dir "/" %) (or (:paths coord) (:paths lib-deps-edn) ["src"])))

(defn- read-deps-edn [dir]
  (let [f (str dir "/deps.edn")]
    (when (File/Exists f)
      (edn/read-string (slurp f)))))

(defn- procure
  "Fetch one coord, returning {:dir :sha}. Git clones into the cache and
  verifies the pin; :local/root is used in place (no sha)."
  [cache {:keys [git/url git/sha git/tag local/root] :as coord}]
  (cond
    url   (git/procure! cache url sha tag)
    root  {:dir root :sha nil}
    :else (throw (ex-info "Unsupported coord (need :git/url or :local/root)"
                          {:coord coord}))))

(defn resolve-deps
  "Breadth-first transitive resolution of a deps map. Closest-wins: the
  first sighting of a lib (nearest the root) is kept; later sightings are
  ignored, warning only on a genuine commit divergence (a short sha and the
  full sha it abbreviates are treated as equal). An entry in overrides
  replaces a lib's coord wherever it is encountered (a JVM->CLR fork swap),
  without seeding a root dependency for libs absent from the tree. Returns
  lib -> {:coord :resolved-sha :paths}."
  [cache deps overrides]
  (loop [queue   (vec deps)
         out     {}
         skipped []]
    (if-let [[lib coord0] (first queue)]
      (let [more  (subvec queue 1)
            coord (get overrides lib coord0)]
        (cond
          (runtime-provided? lib)
          (recur more out skipped)

          (contains? out lib)
          (let [kept (:resolved-sha (out lib))
                cur  (or (:git/sha coord) (:git/tag coord))]
            (when (and kept cur (not (git/same-commit? kept cur)))
              (println "WARN: conflicting version for" lib
                       "-> kept" kept "ignored" cur))
            (recur more out skipped))

          (not (native-coord? coord))
          (recur more out (conj skipped lib))

          :else
          (let [{:keys [dir sha]} (procure cache coord)
                child             (read-deps-edn dir)]
            (recur (into more (vec (:deps child)))
                   (assoc out lib {:coord        coord
                                   :resolved-sha (or sha (:git/sha coord) (:git/tag coord))
                                   :paths        (lib-paths dir coord child)})
                   skipped))))
      (do
        (when (seq skipped)
          (println "Note: skipped" (count skipped) "non-native (maven) dep(s):"
                   (str/join ", " skipped)))
        out))))

(defn- cache-root
  "GITLIBS (the tools.deps cache variable) if set, namespaced under nostrand/
  so CLR clones stay distinguishable from the JVM entries in a shared dir;
  else ~/.nostrand/gitlibs."
  []
  (if-let [gitlibs (Environment/GetEnvironmentVariable "GITLIBS")]
    (str gitlibs "/nostrand")
    (str (Environment/GetEnvironmentVariable "HOME") "/.nostrand/gitlibs")))

(defn create-basis
  "Read deps-file, fold in the selected aliases, resolve transitively,
  and return {:paths :libs :classpath-paths}."
  ([] (create-basis "deps.edn" []))
  ([deps-file aliases]
   (let [{:keys [paths deps overrides]} (merge-aliases (edn/read-string (slurp deps-file)) aliases)
         libs (resolve-deps (cache-root) deps overrides)]
     {:paths paths
      :libs  libs
      :classpath-paths (concat paths (mapcat :paths (vals libs)))})))
