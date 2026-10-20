(ns
 ^{:doc "Runs a task named on the command line, as clojure.run.exec does for
  `clojure -X`."}
 nostrand.exec
  (:require clojure.main
            [clojure.edn :as edn]
            [clojure.string :as string])
  (:import [System.IO StringReader]
           [clojure.lang PushbackTextReader]))

(defonce ^{:doc "Namespaces searched in order for an unqualified task name."}
  task-namespaces
  (atom '[nostrand.tasks]))

(defn register-tasks
  "Require ns-sym and append it to task-namespaces."
  [ns-sym]
  (require ns-sym)
  (swap! task-namespaces #(if (some #{ns-sym} %) % (conj % ns-sym))))

(defn- read-forms
  "Every EDN form in s, or nil when s does not read."
  [s]
  (let [rdr (PushbackTextReader. (StringReader. s))]
    (try
      (loop [forms []]
        (let [form (edn/read {:eof ::eof} rdr)]
          (if (= ::eof form)
            forms
            (recur (conj forms form)))))
      (catch Exception _ nil))))

(defn read-args
  "args read as EDN together, so a map may span args, else one by one, with an
  arg that does not read, such as an absolute path, taken as a symbol."
  [args]
  (or (read-forms (string/join " " args))
      (into [] (mapcat #(or (read-forms %) [(symbol %)])) args)))

(defn- resolve-task
  [task]
  (when-not (symbol? task)
    (throw (ex-info (str "Not a task name: " (pr-str task)) {:task task})))
  (if-let [ns-name (namespace task)]
    (or (ns-resolve (doto (symbol ns-name) require) (symbol (name task)))
        (throw (ex-info (str "Namespace " ns-name " loaded but function not found: " (name task))
                        {:task task})))
    (or (some #(get (ns-interns %) task) @task-namespaces)
        (throw (ex-info (str "No task named " task " in "
                             (string/join ", " @task-namespaces))
                        {:task task})))))

(defn -main
  "Call the task the first arg names with the rest, all read as EDN."
  [& args]
  (let [[task & task-args] (read-args args)]
    (apply (resolve-task task) task-args)))

(defn run
  "Run a task as `nos` does, in `user`, and return its value or throw its
  error."
  [args]
  (clojure.main/with-bindings
    (in-ns 'user)
    (set! *command-line-args* (seq args))
    (apply -main args)))
