(ns magic.log
  "Output helpers for the bb tasks: plain lines, status on stdout (println),
   warnings on stderr, fail! for the print-and-exit-non-zero pattern.")

(defn warn
  "Print each line to stderr."
  [& lines]
  (binding [*out* *err*]
    (run! println lines)))

(defn fail!
  "Print lines to stderr, then exit the task non-zero with msg (which bb
   prints as the error)."
  [msg & lines]
  (apply warn lines)
  (throw (ex-info msg {:babashka/exit 1})))
