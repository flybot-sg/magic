(ns nostrand.deps.nuget
  (:require [nostrand.deps.shell :refer [sh]]))

(defn pack-and-push-nuget
  "Packs the dlls and push it to the online repo.
   prerequisite: you need to add at the root of the project:
   - A `.csproj`     : dependency manager
   - A `.nuspec`     : package manager
   - A `nuget.config`: host repo credentials (keep this file locally)"
  [git-host-type & {:keys [with-build? configuration]
                    :or   {with-build? true configuration "Release"}}]
  (println "packing...")
  (let [{:keys [exit out err]} (sh "dotnet" (str "pack --configuration " configuration
                                                 (when-not with-build? " --no-build")))]
    (when (= 1 exit)
      (throw (ex-info "error while packing" {:out out :err err}))))
  (println "pushing to " git-host-type "...")
  (let [{:keys [exit out err]} (sh "dotnet"
                                   (str "nuget push bin/" configuration "/*.nupkg "
                                        "--source " git-host-type))]
    (when (= 1 exit)
      (throw (ex-info "error while pushing" {:out out :err err})))))
