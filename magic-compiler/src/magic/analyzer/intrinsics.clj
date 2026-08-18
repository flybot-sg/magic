(ns magic.analyzer.intrinsics
  (:require
    [clojure.tools.analyzer.utils :refer [resolve-sym]]
    [magic.analyzer
     [uniquify :refer [uniquify-locals]]
     [util :as util]]))

(defonce intrinsic-forms (atom {}))

(defonce intrinsic-methods (atom {}))

(defn register-intrinsic-form [sym type-fn il-fn]
  (if (namespace sym)
    (swap! intrinsic-forms assoc sym {::type type-fn ::il il-fn})
    (throw (ex-info "Must use fully qualified symbol" {:got sym}))))

(defn register-intrinsic-method
  "Register an intrinsic for the static method a var's :inline lowers to, so
  an already-inlined call still takes the intrinsic when its types qualify."
  [type method-name arg-count type-fn il-fn]
  (swap! intrinsic-methods assoc [(.FullName type) method-name arg-count]
         {::type type-fn ::il il-fn}))

(defn- origin-var
  "The var whose :inline expansion produced this node, resolved from the last
  pre-expansion form. Two vars can inline to the same method with different
  semantics (inc and unchecked-inc), so the origin var outranks the method
  table."
  [{:keys [raw-forms env]}]
  (let [form (last raw-forms)
        v (when (seq? form) (resolve-sym (first form) env))]
    (when (var? v) v)))

(defn- intrinsic-entry [{:keys [op method args] :as ast fn-ast :fn}]
  (case op
    :invoke (when-let [v (:var fn-ast)]
              (@intrinsic-forms (util/var-symbol v)))
    :static-method (or (when-let [v (origin-var ast)]
                         (@intrinsic-forms (util/var-symbol v)))
                       (@intrinsic-methods [(-> method .DeclaringType .FullName)
                                            (.Name method)
                                            (count args)]))
    nil))

(defn analyze
  "Analyze invoke and static method forms into CLR intrinsics if possible"
  {:pass-info {:walk :post :after #{#'uniquify-locals}}}
  [ast]
  (if-let [bc-fn (intrinsic-entry ast)]
    (if-let [bc-type ((::type bc-fn) ast)]
      (merge ast
             {:op :intrinsic
              :il-fn (::il bc-fn) ;; TODO this is basically a "compiler"... need better name
              :type bc-type
              :original ast})
      ast)
    ast))
