(ns rama-clojure-starter.core-test
  (:use [clojure.test]
        [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [ardoq-rama-poc.module.core :as sut :refer [ArdoqCore]])
  (:import (java.util UUID)))

(defn uuid
  ([] (random-uuid))
  ([n] (UUID/fromString
         (format "ffffffff-ffff-ffff-ffff-%012d" n))))

(deftest crud-create-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})

    (let [component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot")
          component-edits-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-edits")
          component-by-id (foreign-pstate ipc (get-module-name ArdoqCore) "$$component-by-id")
          comp1 (sut/->comp {:_id (uuid 1) :name "first"})]
      (testing "new component"
        (is (= comp1
               (get (foreign-append! component-depot comp1) "component"))
            "The created component gets returned")
        (is (= comp1
               (foreign-select-one [(keypath (uuid 1))] component-by-id))
            "The component is in the PState"))
      (testing "idempotency - repeated create"
        (is (= comp1
               (get (foreign-append! component-depot (assoc comp1 :ignored "indeed")) "component"))
            "The create is ignored, the existing component is returned")
        (is (= comp1
               (foreign-select-one [(keypath (uuid 1))] component-by-id))
            "The original, unchanged component is in the PState")))))

(deftest crud-delete-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})

    (let [component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot")
          *component-deletes-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-deletes")
          component-by-id (foreign-pstate ipc (get-module-name ArdoqCore) "$$component-by-id")
          comp1 (sut/->comp {:_id (uuid 1) :name "first" :f1 1, :f2 true, :f3 nil})]
      (foreign-append! component-depot comp1)
      (is (= (uuid 1)
             (foreign-select-one [(keypath (uuid 1) :_id)] component-by-id))
          "Precondition: exists")
      (is (= {}
             (foreign-append! *component-deletes-depot (sut/->ComponentDelete (uuid 1)))))
      (is (= nil
             (foreign-select-one [(keypath (uuid 1))] component-by-id))
          "Doesn't exist anymore")
      )
  ))

(deftest crud-update-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})

    (let [component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot")
          component-edits-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-edits")
          component-by-id (foreign-pstate ipc (get-module-name ArdoqCore) "$$component-by-id")
          comp1 (sut/->comp {:_id (uuid 1) :name "first" :f1 1, :f2 true, :f3 nil})]
      (assert (= comp1
                 (get (foreign-append! component-depot comp1) "component")))
      (testing "CAS ok (current values = those the client has)"
        (testing "single field update"
         (is (= {}
                (foreign-append! component-edits-depot
                                 (sut/map->ComponentEdits
                                   {:_id (uuid 1)
                                    :edits [(sut/map->ComponentEdit
                                              {:field :name
                                               :before "first"
                                               :after "primero"})]})))
             "Append return an empty map on success (just because...)")
         (is (= "primero"
                (foreign-select-one [(keypath (uuid 1) :name)] component-by-id))
             "The :name got updated"))
        (testing "new field"
         (is (= {}
                (foreign-append! component-edits-depot
                                 (sut/map->ComponentEdits
                                   {:_id (uuid 1)
                                    :edits [(sut/map->ComponentEdit
                                              {:field :new
                                               :before nil
                                               :after "created"})]})))
             "Append return an empty map on success (just because...)")
         (is (= "created"
                (foreign-select-one [(keypath (uuid 1) :new)] component-by-id))
             "The ::new got added"))
        (testing "multi field update"
          (foreign-append!
            component-depot
            (sut/->comp {:_id (uuid 2) :name "Bob" :f1 1, :f2 true, :f3 nil}))
          (is (= {}
                 (foreign-append! component-edits-depot
                                  (sut/map->ComponentEdits
                                    {:_id (uuid 1)
                                     :edits [(sut/map->ComponentEdit
                                               {:field :f1
                                                :before 1
                                                :after 2})
                                             (sut/map->ComponentEdit
                                               {:field :f2
                                                :before true
                                                :after false})
                                             (sut/map->ComponentEdit
                                               {:field :f3
                                                :before nil
                                                :after "xxx"})]})))
              "Append return an empty map on success (just because...)")
          (is (= {:f1 2, :f2 false, :f3 "xxx"}
                 (-> (foreign-select-one [(keypath (uuid 1))] component-by-id)
                     (select-keys [:f1 :f2 :f3])))
              "The :name got updated")))
      (testing "CAS fail (fields updated in the meantime)"
        (is (= {"component" {:data {:f2 [true false]}
                             :error "Compare-and-set failed, the DB value differs from the value the client expected."}}
               (foreign-append! component-edits-depot
                                (sut/map->ComponentEdits
                                  {:_id (uuid 1)
                                   :edits [(sut/map->ComponentEdit
                                             {:field :f1 ; ok
                                              :before 2
                                              :after 3})
                                           (sut/map->ComponentEdit
                                             {:field :f2 ; conflict
                                              :before true
                                              :after false})]})))
            "Current value != expected => error is reported"))
      (testing "entity doesn't exist"
        (is (= {"component" {:data {:_id (uuid 100)}
                             :error "The entity does not exist"}}
               (foreign-append! component-edits-depot
                                (sut/map->ComponentEdits
                                  {:_id (uuid 100)
                                   :edits [(sut/map->ComponentEdit
                                             {:field :x :before 2 :after 3})]})))
            "Attempted edit of non-existing entity ...")))))
