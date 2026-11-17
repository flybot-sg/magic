(ns magic.test.common
  (:require [magic.api :as m]
            [clojure.template :as temp]
            [clojure.clr.shell :as shell]
            clojure.test))

(defmacro cljclr=magic [& exprs]
  `(do ~@(map (fn [expr] `(clojure.test/is (= ~expr (m/eval (quote ~expr))))) exprs)))

(defmacro is [form]
  `(clojure.test/is (= ~form (m/eval (quote ~form)))))

(defmacro are
  [argv expr & args]
  (if (or
       ;; (are [] true) is meaningless but ok
       (and (empty? argv) (empty? args))
       ;; Catch wrong number of args
       (and (pos? (count argv))
            (pos? (count args))
            (zero? (mod (count args) (count argv)))))
    `(temp/do-template ~argv (clojure.test/is (= ~expr) (m/eval (quote ~expr))) ~@args)
    (throw (ArgumentException. "The number of args doesn't match are's argv."))))

(defn temp-dir []
  (let [dir (System.IO.Path/Combine (System.IO.Path/GetTempPath)
                                    (str "magic-test-" (gensym)))]
    (System.IO.Directory/CreateDirectory dir)
    dir))

(defn nos
  "Run the nos running this suite with args in dir: {:exit :out :err}."
  [dir & args]
  (apply shell/sh
         (.. System.Diagnostics.Process GetCurrentProcess MainModule FileName)
         (.Location (System.Reflection.Assembly/GetEntryAssembly))
         (concat args [:dir dir])))
