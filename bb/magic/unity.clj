(ns magic.unity
  "Babashka helpers for the MAGIC Unity package: sync its UPM version and drive
   the `magic-unity-coexist` repro."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def ^:private default-pkg "magic-unity")
(def ^:private coexist-proj "unity-examples/magic-unity-coexist")
(def ^:private unity
  "/Applications/Unity/Hub/Editor/2022.3.62f3/Unity.app/Contents/MacOS/Unity")

(defn sync-upm-version!
  "Sync magic-unity/package.json version with version.edn. UPM manifests are
   not covered by Directory.Build.props, so this keeps them in lockstep."
  []
  (let [version (:version (edn/read-string (slurp "version.edn")))
        path    (str default-pkg "/package.json")
        content (slurp path)
        updated (str/replace content
                             (re-pattern "\"version\":\\s*\"[^\"]+\"")
                             (str "\"version\": \"" version "\""))]
    (if (= content updated)
      (println "magic-unity/package.json version already in sync (" version ")")
      (do (spit path updated)
          (println "Updated magic-unity/package.json version to" version)))))

