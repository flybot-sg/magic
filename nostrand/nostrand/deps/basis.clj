(ns nostrand.deps.basis
  (:import [System.IO File Path])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nostrand.deps.git :as git]))

(def ^:private default-paths ["src"])

(defn- printerrln
  "println to stderr, keeping stdout clean for task output."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

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

(defn- git-coord? [{:keys [git/sha git/tag]}] (boolean (or sha tag)))

(def ^:private git-services
  "Group-to-url patterns cljr infers from, in clojure.tools.deps.extensions.git."
  [[#"^(?:com|io)\.github\.([^.]+)$"       "https://github.com/%s/%s.git"]
   [#"^(?:com|io)\.gitlab\.([^.]+)$"       "https://gitlab.com/%s/%s.git"]
   [#"^(?:org|io)\.bitbucket\.([^.]+)$"    "https://bitbucket.org/%s/%s.git"]
   [#"^(?:com|io)\.beanstalkapp\.([^.]+)$" "https://%s.git.beanstalkapp.com/%s.git"]
   [#"^ht\.sr\.([^.]+)$"                   "https://git.sr.ht/~%s/%s"]])

(defn- auto-git-url
  [lib]
  (when-let [group (namespace lib)]
    (some (fn [[pattern url]]
            (when-let [[_ owner] (re-matches pattern group)]
              (format url owner (name lib))))
          git-services)))

(defn- resolve-root
  "Absolute path for a coord root: as given when already rooted, else resolved
  against base."
  [base root]
  (if (Path/IsPathRooted root)
    root
    (Path/GetFullPath (Path/Combine base root))))

(defn- canonicalize
  "Coord as tools.deps reads it: the legacy :sha and :tag spellings folded into
  :git/sha and :git/tag, the :git/url a hosted-service lib name implies, and
  :local/root resolved against the directory of the deps file declaring it."
  [lib base {unsha :sha untag :tag :as coord}]
  (when (and unsha (:git/sha coord))
    (throw (ex-info "git coord has both :sha and :git/sha" {:lib lib :coord coord})))
  (when (and untag (:git/tag coord))
    (throw (ex-info "git coord has both :tag and :git/tag" {:lib lib :coord coord})))
  (let [coord (cond-> (dissoc coord :sha :tag)
                unsha (assoc :git/sha unsha)
                untag (assoc :git/tag untag)
                (:local/root coord) (update :local/root #(resolve-root base %)))]
    (if (git-coord? coord)
      (if-let [url (or (:git/url coord) (auto-git-url lib))]
        (assoc coord :git/url url)
        (throw (ex-info (str "Failed to infer git url for: " lib)
                        {:lib lib :coord coord})))
      coord)))

(def ^:private unsupported-alias-keys
  [:deps :paths :replace-deps :replace-paths :default-deps :classpath-overrides])

(defn- merge-aliases
  "Fold the selected aliases into {:paths :deps :overrides}. :extra-paths
  append to :paths; :extra-deps merge onto the dep set; :override-deps are
  kept separate (an override swaps a lib's coord wherever it is encountered
  in the tree, without itself seeding a root dependency). Selected keywords
  not declared under :aliases warn and are skipped, matching tools.deps."
  [{:keys [paths deps aliases]} alias-kws]
  (when-let [undeclared (seq (remove #(contains? aliases %) (distinct alias-kws)))]
    (printerrln "WARNING: Specified aliases are undeclared and are not being used:"
                (vec undeclared)))
  (let [selected (keep #(get aliases %) alias-kws)]
    (when-let [unsupported (seq (distinct (mapcat #(filter % unsupported-alias-keys) selected)))]
      (printerrln "WARNING: alias key(s) cljr honours and nos ignores:" (vec unsupported)))
    {:paths     (into (vec (or paths default-paths)) (mapcat :extra-paths selected))
     :deps      (apply merge deps (map :extra-deps selected))
     :overrides (apply merge (map :override-deps selected))}))

(defn- lib-paths
  "Absolute source paths a resolved lib contributes, rooted at its checkout
  dir. Preference: an explicit :paths on the coord (for git/local deps whose
  repo has no deps file or a non-src layout, e.g. a pom-only contrib lib under
  src/main/clojure), else the lib's own deps file :paths, else [\"src\"]."
  [dir coord lib-deps-edn]
  (map #(str dir "/" %) (or (:paths coord) (:paths lib-deps-edn) default-paths)))

(defn- read-deps-edn
  "A dependency's own deps map: deps-clr.edn if present, else deps.edn.
  Same preference cljr applies to a dep's paths and deps."
  [dir]
  (let [clr (str dir "/deps-clr.edn")
        f   (if (File/Exists clr) clr (str dir "/deps.edn"))]
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
  full sha it abbreviates are treated as equal). A coord's :exclusions prune
  those libs from its subtree, and an entry in overrides replaces a lib's coord
  wherever it is encountered (a JVM->CLR fork swap), without seeding a root
  dependency for libs absent from the tree. Returns
  lib -> {:coord :resolved-sha :paths}."
  [cache deps overrides]
  (loop [queue   (vec (for [[lib coord] deps] [lib coord "." #{}]))
         out     {}
         skipped []]
    (if-let [[lib coord0 base excluded] (first queue)]
      (let [more  (subvec queue 1)
            coord (canonicalize lib base (get overrides lib coord0))]
        (cond
          (runtime-provided? lib)
          (recur more out skipped)

          (contains? out lib)
          (let [kept (:resolved-sha (out lib))
                cur  (or (:git/sha coord) (:git/tag coord))]
            (when (and kept cur (not (git/same-commit? kept cur)))
              (printerrln "WARN: conflicting version for" lib
                          "-> kept" kept "ignored" cur))
            (recur more out skipped))

          (not (native-coord? coord))
          (recur more out (conj skipped lib))

          :else
          (let [{:keys [dir sha]} (procure cache coord)
                dir               (cond-> dir
                                    (and (:git/url coord) (:deps/root coord))
                                    (resolve-root (:deps/root coord)))
                child             (read-deps-edn dir)
                excluded          (into excluded (:exclusions coord))]
            (recur (into more (for [[l c] (:deps child)
                                    :when (not (contains? excluded l))]
                                [l c dir excluded]))
                   (assoc out lib {:coord        coord
                                   :resolved-sha (or sha (:git/sha coord) (:git/tag coord))
                                   :paths        (lib-paths dir coord child)})
                   skipped))))
      (do
        (when (seq skipped)
          (printerrln "Note: skipped" (count skipped) "non-native (maven) dep(s):"
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

(defn project-deps-file
  "deps-clr.edn if present, else deps.edn. Matches cljr, which reads
  deps-clr.edn in place of deps.edn."
  []
  (if (File/Exists "deps-clr.edn") "deps-clr.edn" "deps.edn"))

(defn read-project-deps
  "Parse the project deps file, naming it in the error when it is missing
  or malformed (a bare slurp/reader failure names neither)."
  [deps-file]
  (when-not (File/Exists deps-file)
    (throw (ex-info (str "Deps file not found: " deps-file) {:file deps-file})))
  (try
    (edn/read-string (slurp deps-file))
    (catch Exception e
      (throw (ex-info (str "Malformed " deps-file ": " (.Message e))
                      {:file deps-file} e)))))

(defn create-basis
  "Read deps-file, fold in the selected aliases, resolve transitively,
  and return {:paths :libs :classpath-paths}."
  ([] (create-basis (project-deps-file) []))
  ([deps-file aliases]
   (let [{:keys [paths deps overrides]} (merge-aliases (read-project-deps deps-file) aliases)
         libs (resolve-deps (cache-root) deps overrides)]
     {:paths paths
      :libs  libs
      :classpath-paths (concat paths (mapcat :paths (vals libs)))})))
