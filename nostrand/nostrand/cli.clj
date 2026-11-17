(ns
 ^{:author "Ramsey Nasser"}
 nostrand.cli
  (:import
   [Nostrand Nostrand]
   [System.IO Directory Path])
  (:require [nostrand.repl :as repl]
            [clojure.string :as string]
            clojure.repl))

(defn- msg
  ([header body]
   (Nostrand.Terminal/Message header body))
  ([header body color]
   (Nostrand.Terminal/Message header body color)))

(defn version []
  (msg "Nostrand" (Nostrand/Version) ConsoleColor/Cyan)
  (msg "Clojure.Runtime" (Nostrand/ClojureRuntimeVersion) ConsoleColor/Cyan)
  (msg "Magic.Runtime" (Nostrand/MagicRuntimeVersion) ConsoleColor/Cyan)
  (msg "Clojure" (clojure-version) ConsoleColor/Cyan)
  (msg "CLR Runtime" (str (Environment/get_Version)
                          " (" (Environment/get_OSVersion) ")")
       ConsoleColor/DarkGray))

(defn where
  "Print the directory the running host loaded Clojure.dll from, as bare
  stdout for shell capture. With a file name, print that file's full path.
  Usage: nos where                  ; the runtime assemblies dir
         nos where Clojure.dll      ; full path of one assembly"
  ([] (println (-> (.Assembly clojure.lang.RT) .Location Path/GetDirectoryName)))
  ([file] (println (Path/Combine (-> (.Assembly clojure.lang.RT) .Location Path/GetDirectoryName)
                                 (str file)))))

(defn socket-repl [args]
  (repl/socket args))

(defn repl
  ([]
   (version)
   (repl/cli-repl))
  ([port]
   (version)
   (repl/cli-repl)))

(defn tasks []
  (let [ns-syms
        (->> (Directory/GetFiles "." "*.clj")
             (map #(-> %
                       (string/replace "./" "")
                       (string/replace ".clj" "")
                       symbol)))]
    (doseq [s ns-syms]
      (require s)
      (let [fns (->> s
                     find-ns
                     ns-publics
                     vals)]
        (doseq [f fns]
          ((var clojure.repl/print-doc) (meta f)))))))

(defn clojure-socket-repl [args]
  (print "Starting Clojure socket repl...")
  (let [opts (repl/clojure-socket-server args)]
    (println "done ")
    (println "Started socket repl with Options: " opts)
    (repl)))
