(ns ardoq-rama-poc.module.core
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest] ;; FIXME rm
            )
  (:import (clojure.lang Keyword)
           (java.util UUID)))

(defrecord ComponentCreate [_id name #_rootWorkspace])
(defrecord ComponentEdit [field before after])
(defrecord ComponentEdits [_id edits])

(defn ->comp [m]
  {:pre [(uuid? (:_id m))]}
  (map->ComponentCreate m))

(def as-map (partial into {}))

(defmodule ArdoqCore [setup topologies]
  (declare-depot setup *component-depot (hash-by :_id)) ; TODO Include orgid in partitioning for tenant isolation
  (declare-depot setup *component-edits (hash-by :_id)) ; TODO Include orgid in partitioning for tenant isolation
  (let [s (stream-topology topologies "component")]
    (declare-pstate s $$component-by-id {UUID (map-schema Keyword Object {:subindex? true})}) ; see also fixed-keys-schema
    (<<sources s

      ;; CREATES
      (source> *component-depot :> *component)
      (local-select> [(keypath (:_id *component)) (view as-map)] $$component-by-id :> *existing-component) ; view <> subindexed map not serializable
      (println "existing-component" *existing-component)
      (<<if (nil? *existing-component)
        (local-transform> [(keypath (:_id *component))
                           (termval *component)] $$component-by-id))
      (ack-return> (or> *existing-component *component))

      ;; UPDATES
      (source> *component-edits :> {:keys [*_id *edits]})
      (ops/explode *edits :> {:keys [*field *before *after]})
      (<<shadowif *field string? (keyword *field)) ; TODO is this the optimal way?
      (local-select> (keypath *_id *field) $$component-by-id :> *existing-val)
      ;; FIXME: Keeps retrying and failing!
      (<<if (= *existing-val *before)
        (local-transform> [(keypath *_id *field) (termval *after)] $$component-by-id)
        (else>)
        (ack-return> {:message "Compare-and-set failed, the DB value differs from the value the client expected."
                      :data {:field *field :db-val *existing-val :client-val *before}}))))
  )

(defn uuid
  ([] (random-uuid))
  ([n] (UUID/fromString
         (format "ffffffff-ffff-ffff-ffff-%012d" n))))

(comment

  (some? ipc)
  (defonce ipc (rtest/create-ipc)) ; (close! ipc)
  (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})
  (rtest/update-module! ipc ArdoqCore)

  (do
    (def component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot"))
    (def component-edits-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-edits"))
    (def component-by-id (foreign-pstate ipc (get-module-name ArdoqCore) "$$component-by-id")))

  ;; CREATE
  (get (foreign-append! component-depot (->comp {:_id (uuid 1) :name "first"})) "component")
  ;; UPDATE
  (->
   (foreign-append! component-edits-depot
                    (map->ComponentEdits {:_id (uuid 1)
                                          :edits [(map->ComponentEdit {:field "name"
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

  )