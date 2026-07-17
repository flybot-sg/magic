(ns magic.test.protocol
  (:require [clojure.test :refer [deftest is]]
            [magic.api :as m]))

(defn- protocol-hint-cases []
  ;; A ^Protocol parameter must not narrow to the generated interface: an
  ;; extend-protocol type is not an instance of it, so a cast at the invoke
  ;; boundary would throw. Dispatch stays polymorphic through the protocol fn.
  [(m/eval '(do (defprotocol PLen (plen [this]))
                (extend-protocol PLen System.String
                  (plen [this] (.Length this)))
                (defn hinted [^PLen s] (plen s))
                (hinted "hello")))
   ;; A deftype implementer still works through the same hinted fn.
   (m/eval '(do (defprotocol PVal (pval [this]))
                (deftype TVal [] PVal (pval [this] 42))
                (defn hinted [^PVal s] (pval s))
                (hinted (TVal.))))
   ;; Member resolution goes through Object, not the interface (which does not
   ;; expose Object members).
   (m/eval '(do (defprotocol PShow (pshow [this]))
                (defn showit [^PShow x] (.ToString x))
                (showit 42)))
   ;; The interface may reach the hint as an import from the protocol's own
   ;; namespace (how clojure.data.json hints its Appendable protocol), so the
   ;; bare tag resolves to the imported interface, not the protocol var.
   (m/eval '(do (ns magic.test.protocol.sink)
                (defprotocol Sink (sink-append [this s]))
                (extend-protocol Sink System.IO.StringWriter
                  (sink-append [this s] (.Write this s) this))
                (ns magic.test.protocol.consumer
                  (:import (magic.test.protocol.sink Sink)))
                (defn write-it [^Sink out]
                  (magic.test.protocol.sink/sink-append out "imported")
                  (.ToString out))
                (write-it (System.IO.StringWriter.))))
   ;; Same, but the protocol lives in a hyphenated namespace, so its interface
   ;; is imported by the munged package name and recovering the protocol var
   ;; must demunge the namespace.
   (m/eval '(do (ns magic.test.protocol.dashed-lib)
                (defprotocol Sink2 (append2 [this s]))
                (extend-protocol Sink2 System.IO.StringWriter
                  (append2 [this s] (.Write this s) this))
                (ns magic.test.protocol.dashed-user
                  (:import (magic.test.protocol.dashed_lib Sink2)))
                (defn write-h [^Sink2 out]
                  (magic.test.protocol.dashed-lib/append2 out "hyphenated")
                  (.ToString out))
                (write-h (System.IO.StringWriter.))))])

(deftest protocol-typed-parameter-hint
  (is (= [5 42 "42" "imported" "hyphenated"] (protocol-hint-cases))))
