(ns nostrand.deps.submodules
  "Derive a flat source-path set from git submodules, so a project that vendors
  its dependencies as submodules can treat .gitmodules as the single source of
  truth for its deps.edn :paths instead of hand-maintaining the list."
  (:import [System.IO File Directory])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn parse-gitmodules
  "Parse .gitmodules text into a vector of submodule maps {:name :path :url ...},
  one per [submodule \"...\"] block, in declaration order."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %)
                    (str/starts-with? % "#")
                    (str/starts-with? % ";")))
       (reduce
        (fn [acc line]
          (cond
            (str/starts-with? line "[submodule")
            (conj acc {:name (second (re-find #"\"([^\"]*)\"" line))})

            (and (seq acc) (str/includes? line "="))
            (let [[k v] (map str/trim (str/split line #"=" 2))]
              (conj (pop acc) (assoc (peek acc) (keyword k) v)))

            :else acc))
        [])
       (filterv :path)))

(defn- src-dirs
  "Repo-relative source dirs a submodule at sub-path contributes. Prefer the
  submodule's own deps.edn :paths; else whichever conventional source layouts
  are present on disk; else [\"src\"]."
  [sub-path]
  (let [deps-file (str sub-path "/deps.edn")
        declared  (when (File/Exists deps-file)
                    (try (:paths (edn/read-string (slurp deps-file)))
                         (catch Exception _ nil)))]
    (if declared
      (mapv #(str sub-path "/" %) declared)
      (let [present (filterv #(Directory/Exists (str sub-path "/" %))
                             ["src" "src/main/clojure"])]
        (mapv #(str sub-path "/" %) (if (seq present) present ["src"]))))))

(defn submodule-paths
  "Flat vector of source paths contributed by every submodule in gitmodules-text
  whose path starts with root-prefix (nil or blank = every submodule). Lets
  .gitmodules drive a deps.edn :paths over an in-tree submodule layout."
  ([gitmodules-text] (submodule-paths gitmodules-text nil))
  ([gitmodules-text root-prefix]
   (->> (parse-gitmodules gitmodules-text)
        (map :path)
        (filter #(or (str/blank? root-prefix)
                     (= % root-prefix)
                     (str/starts-with? % (str root-prefix "/"))))
        (mapcat src-dirs)
        distinct
        vec)))
