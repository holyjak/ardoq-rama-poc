(ns ardoq-rama-poc.module.core
  (:require [com.rpl.rama :as r :refer :all]
            [com.rpl.rama.path :as p :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest] ;; TODO rm when done playing in the comment
            [clojure.repl :refer [doc]]
            )
  (:import (clojure.lang Keyword)
           (java.util UUID)))

(defrecord ComponentCreate [_id name #_rootWorkspace])
(defrecord ComponentEdit [field before after])
(defrecord ComponentEdits [_id edits])
(defrecord ComponentDelete [_id])

(defn ->comp [m]
  {:pre [(uuid? (:_id m))]}
  (map->ComponentCreate m))

(defn edits->before+after
  "Return [{:f1 'before',...} {:f1 'after',...}] from the combined edits' :before and :after values."
  [edits]
  (assert (every? keyword? (map :field edits)))
  [(into {}
         (map (juxt :field :before))
         edits)
   (into {}
         (map (juxt :field :after))
         edits)])

(defn diffs
  "What in the map exp. differs from the map act.?"
  [expected actual]
  (not-empty
    (into {}
          (keep (fn [[k exp-val]]
                  (when (not= (get actual k) exp-val)
                    [k [exp-val (get actual k)]])))
                  expected)))

(defn some-select-keys [m ks]
  (some-> m (select-keys ks)))

;; DONE:
;; 1. Basic create-update-delete for "components", where updates have compare-and-set semantics
;; TODO: 1. Delete
;; TODO: 1. Parents - FK verification on create, update + rm child subtree on delete
(defmodule ArdoqCore [setup topologies]
  (declare-depot setup *component-depot (hash-by :_id)) ; TODO Include orgid in partitioning for tenant isolation
  (declare-depot setup *component-edits (hash-by :_id)) ; TODO Include orgid in partitioning for tenant isolation
  (declare-depot setup *component-deletes (hash-by :_id)) ; TODO Include orgid in partitioning for tenant isolation
  (let [s (stream-topology topologies "component")]
    (declare-pstate s $$component-by-id {UUID (map-schema Keyword Object)}) ; see also fixed-keys-schema
    (declare-pstate s $$children {UUID #{UUID}}) ; no need for subindexed, don't expect more than 10s
    (<<sources s

      ;; CREATES
      ;; Idempotent: If the entity already exists, return it, else create it

      (source> *component-depot :> {*comp-id :_id :keys [*parent] :as *component})
      (local-select> [(keypath *comp-id)] $$component-by-id :> *existing-component)
      (<<if (nil? *existing-component)
        ;(anchor> <start>)
        ;; Do integrity checks and save it

        ;; Check parent valid, if provided
        (<<if *parent
          (|hash *parent)
          (local-select> (view contains? *parent) $$component-by-id :> *parent-exists?)
          (|hash *comp-id) ; come back to the original partition
          (ifexpr (not *parent-exists?)
                  {:error "The parent entity does not exist"
                   :data {:_id *parent}} :> *error)
          (else>)
          (identity nil :> *error))

        ;; Save the new component (if valid)
        (<<if (not *error) ; TODO clj-kondo complains about unresolved symbol https://clojurians.slack.com/archives/C05N2M7R6DB/p1709709981543949
          (local-transform> [(keypath *comp-id)
                             (termval *component)] $$component-by-id)
          (<<if *parent
            (|hash *parent)
            ;; FIXME Different partition = no transactional semantics => the parent could have been deleted in the meantime...
            ;; => should check $$component-by-id first and undo the creation if the parent doesn't exist or something...
            (local-transform> [(keypath *parent) NONE-ELEM (termval *comp-id)] $$children)))

        (else>)
        (identity nil :> *error))

      (ack-return> (or> *error
                        *existing-component
                        *component))

      ;; UPDATES
      (source> *component-edits :> {:keys [*_id *edits]})
      (edits->before+after *edits :> [*before *after])
      (local-select> [(keypath *_id) (view some-select-keys (keys *before))] $$component-by-id :> *existing-raw)
      (identity (merge (zipmap (keys *before) (repeat nil)) *existing-raw) :> *existing) ; *bef. may have {:k nil} while *ex. may omit the key, for us both cases =
      (identity (= *before *existing) :> *unchanged-since-read?)

      (<<cond
        (case> (nil? *existing-raw))
        (ack-return> {:error "The entity does not exist"
                      :data {:_id *_id}})

        (case> (not *unchanged-since-read?))
        (ack-return> {:error "Compare-and-set failed, the DB value differs from the value the client expected."
                      :data (diffs *before *existing)})

        ;; TODO Check parent valid, if provided
        #_(case> (invalid-parent> *component-edits)
               (ack-return> {:error "Invalid parent"
                             :data :TODO}))

        (default>)
        (local-transform> [(keypath *_id) (submap (keys *after)) (termval *after)] $$component-by-id))

      ;; DELETES TODO: Also children, later refs
      ;; Idempotent: If the entity doesn't exist, nothing happens
      (source> *component-deletes :> *component-delete)
      (local-select> [(must (:_id *component-delete))] $$component-by-id :> *existing-component)
      (ifexpr *existing-component
              ;; this is never false, since the select doesn't emit in such case; can be likely simplified,
              ;; see https://clojurians.slack.com/archives/C05N2M7R6DB/p1708899777191999
              (local-transform> [(keypath (:_id *component-delete)) NONE>] $$component-by-id))
      #_(ack-return> *existing-component) ; option: return the deleted entity, iff it existed
      )))

(defn uuid
  ([] (random-uuid))
  ([n] (UUID/fromString
         (format "ffffffff-ffff-ffff-ffff-%012d" n))))

(comment

  (with-open [ps (rtest/create-test-pstate {UUID (map-schema Keyword Object)})]
    (rtest/test-pstate-transform [(keypath (uuid 1)) (termval {:n 1, :x :ignored})] ps)
    (?<- (local-select> [(keypath (uuid 2))] ps :> *existing)
      (println "HERE" *existing)))

  (defonce ipc (rtest/create-ipc)) ; (close! ipc)
  (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})
  (rtest/update-module! ipc ArdoqCore)

  (do
    (def component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot"))
    (def component-edits-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-edits"))
    (def component-by-id (foreign-pstate ipc (get-module-name ArdoqCore) "$$component-by-id")))

  ;; CREATE
  (get (foreign-append! component-depot (->comp {:_id (uuid 1) :name "first"})) "component")
  (foreign-append! component-depot (->comp {:_id (uuid 2) :name "second"}))
  ;; UPDATE
  (->
   (foreign-append! component-edits-depot
                    (map->ComponentEdits {:_id (uuid 1)
                                          :edits [(map->ComponentEdit {:field :name
                                                                       :before "first"
                                                                       :after "primero"})]}))
   (get "component"))

  (foreign-select-one [(keypath (uuid 1)) (view as-map)] component-by-id)
  (foreign-select-one (keypath (uuid 1) :name) component-by-id)
  (foreign-select-one (keypath #uuid"ffffffff-ffff-ffff-ffff-000000000004") component-by-id)
  (foreign-select [(keypath (uuid 1)) ALL] component-by-id)
  ; None here works, b/c subindexed map could possibly be huge and thus isn't serializable
  (foreign-select [ALL] component-by-id)
  (foreign-select [MAP-KEYS] component-by-id)
  (foreign-select [(keypath)] component-by-id)
  ; --------------

  (com.rpl.ramaspecter/select-one (keypath 123) {123 {:b {:c 1}}})

  (?<-
    (clojure.core/identity "a" :> *x)
    (println *x))

  (?<-
    (identity "a" :> *x)
    (identity "b" :> *y)
    (str *x *y 1 "!" 2 :> *z)
    (println *z))


  ;; Src https://redplanetlabs.com/docs/~/tutorial4.html#_conditionals
  (?<-
    (<<if (= 1 2)
         (println "math is dead")
         (else>) (println "math is alive"))
    (println "cond done"))

  (?<- (ifexpr true "true" :> *x)
    (:clj> *x))

  (deframafn add-ten [x] (+ x 10)) ; FIXME can't resolve x
  (deframaop my-operation []
    )



  ;; LESSONS LEARNED
  ;; Can use `(clojure.core/identity "a" :> *x)` to set the Rama var *x to "a" in a dataflow
  ;; => some core fns have been updated to be usable in dataflow and support `... :> <ramavar>` at the end of their args


  ,)