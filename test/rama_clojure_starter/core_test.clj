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
          "Doesn't exist anymore"))))

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
          (is (= {"component" :OK}
                 (foreign-append! component-edits-depot
                                  (sut/map->ComponentEdits
                                    {:_id (uuid 1)
                                     :edits [(sut/map->ComponentEdit
                                               {:field :name
                                                :before "first"
                                                :after "primero"})]})))
              "Append return ok on success (just because...)")
          (is (= "primero"
                 (foreign-select-one [(keypath (uuid 1) :name)] component-by-id))
              "The :name got updated"))
        (testing "new field"
          (is (= {"component" :OK}
                 (foreign-append! component-edits-depot
                                  (sut/map->ComponentEdits
                                    {:_id (uuid 1)
                                     :edits [(sut/map->ComponentEdit
                                               {:field :new
                                                :before nil
                                                :after "created"})]})))
              "Append return ok on success (just because...)")
          (is (= "created"
                 (foreign-select-one [(keypath (uuid 1) :new)] component-by-id))
              "The ::new got added"))
        (testing "multi field update"
          (foreign-append!
            component-depot
            (sut/->comp {:_id (uuid 2) :name "Bob" :f1 1, :f2 true, :f3 nil}))
          (is (= {"component" :OK}
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
              "Append return ok on success (just because...)")
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

(defn select-keys-with-defaults [m ks]
  (merge (zipmap ks (repeat nil))
         (select-keys m ks)))

(deftest query-ancestor?-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})

    (let [component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot")
          ancestor? (foreign-query ipc (get-module-name ArdoqCore) "ancestor?")
          ids [10 11 12 13]]
      (->> (map (fn [id parent] (sut/->comp {:_id (uuid id) :parent (some-> parent uuid)}))
                ids (cons nil ids))
           (run! (partial foreign-append! component-depot)))

      (is (foreign-invoke-query ancestor? (uuid 10) (uuid 11)))
      (is (foreign-invoke-query ancestor? (uuid 10) (uuid 12)))
      (is (foreign-invoke-query ancestor? (uuid 11) (uuid 12)))
      (is (not (foreign-invoke-query ancestor? (uuid 11) (uuid 10))))
      (is (not (foreign-invoke-query ancestor? (uuid 12) (uuid 10))))
      (is (not (foreign-invoke-query ancestor? (uuid 12) (uuid 11))))

      (doall
        (for [needle ids
              child ids]
          (is (= (< needle child)
                 (foreign-invoke-query ancestor? (uuid needle) (uuid child)))
              (format "Expected %d %s to be an ancestor of %d, given ancestor chain %s"
                      needle
                      (if (< needle child) "" "NOT")
                      child ids)))))))

(deftest query-ancestors-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})

    (let [component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot")
          ancestors (foreign-query ipc (get-module-name ArdoqCore) "ancestors")
          grandparent (sut/->comp {:_id (uuid 0)})
          parent (sut/->comp {:_id (uuid 1) :parent (uuid 0)})
          child (sut/->comp {:_id (uuid 2) :parent (uuid 1)})]
      (foreign-append! component-depot grandparent)
      (foreign-append! component-depot parent)
      (foreign-append! component-depot child)

      (testing "single level: parent -> grandparent"
        (is (= {(:_id parent) [(:_id grandparent)]}
               (foreign-invoke-query ancestors [(:_id parent)]))))

      (testing "multi-level: child -> parent -> grandparent"
        (is (= {(:_id child) (map :_id [parent grandparent])}
               (foreign-invoke-query ancestors [(:_id child)]))))

      (testing "0 level - a parent-less component"
        (is (= {(:_id grandparent) nil}
               (foreign-invoke-query ancestors [(:_id grandparent)]))))

      (let [ids [10 11 12 13]]
        (->> (map (fn [id parent] (sut/->comp {:_id (uuid id) :parent (some-> parent uuid)}))
                  ids (cons nil ids))
             (run! (partial foreign-append! component-depot)))
        (testing "multi-level - another chain (13 -> .. -> 10)"
          (is (= {(uuid 13) (map uuid (next (reverse ids)))}
                 (foreign-invoke-query ancestors [(uuid 13)]))))

        (testing "multiple inputs & outputs"
          (is (= {(uuid 13) (map uuid [12 11 10])
                  (uuid 12) (map uuid [11 10])
                  (uuid 11) (map uuid [10])
                  (uuid 10) nil
                  (:_id child) (map :_id [parent grandparent])}
                 (foreign-invoke-query ancestors [(uuid 13) (uuid 12)
                                                  (uuid 11) (uuid 10) (:_id child)]))))))))

(deftest parent-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc ArdoqCore {:tasks 4 :threads 2})

    (let [component-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-depot")
          component-edits-depot (foreign-depot ipc (get-module-name ArdoqCore) "*component-edits")
          component-by-id (foreign-pstate ipc (get-module-name ArdoqCore) "$$component-by-id")
          children (foreign-pstate ipc (get-module-name ArdoqCore) "$$children")
          grandparent1 (sut/->comp {:_id (uuid 0) :name "zeroth" :f1 1, :f2 true, :f3 nil})
          parent1 (sut/->comp {:_id (uuid 1) :parent (uuid 0) :name "first" :f1 1, :f2 true, :f3 nil})
          child (sut/->comp {:_id (uuid 2) :parent (uuid 1) :name "child"})
          no-comp-id (uuid 666)]
      (foreign-append! component-depot grandparent1)
      (foreign-append! component-depot parent1)
      (foreign-append! component-depot child)
      (is (= (uuid 1)
             (foreign-select-one [(keypath (uuid 1) :_id)] component-by-id))
          "Precondition: exists")
      (testing "valid parent on create/update"
        (testing "create"
          (testing "valid parent"
            (foreign-append! component-depot (sut/->comp {:_id (uuid 20) :parent (uuid 1) :name "child 1"}))
            (is (= (uuid 1)
                   (foreign-select-one [(keypath (uuid 20) :parent)] component-by-id))
                "The component was created, with the provided valid parent")
            (is (= (uuid 20)
                   (foreign-select-one [(keypath (uuid 1) (uuid 20))] children))
                "The component is also added to the parent's children set"))

          (testing "invalid parent"
            (is (= "The parent entity does not exist"
                   (get-in (foreign-append! component-depot (sut/->comp {:_id (uuid 30) :parent no-comp-id :name "No parent's child"}))
                           ["component" :error])))
            (is (= nil
                   (foreign-select-one [(keypath (uuid 30))] component-by-id))
                "The component was not created b/c of invalid parent")))

        (testing "update"
          (foreign-append! component-depot (sut/->comp {:_id (uuid 40) :name "updatable"}))
          (foreign-append! component-edits-depot
                           (sut/map->ComponentEdits
                             {:_id (uuid 40)
                              :edits [(sut/map->ComponentEdit
                                        {:field :parent :before nil :after (uuid 1)})]}))
          (is (= (uuid 1)
                 (foreign-select-one [(keypath (uuid 40) :parent)] component-by-id))
              "Valid parent is accepted")

          (foreign-append! component-edits-depot
                           (sut/map->ComponentEdits
                             {:_id (uuid 40)
                              :edits [(sut/map->ComponentEdit
                                        {:field :parent :before (uuid 1) :after no-comp-id})
                                      (sut/map->ComponentEdit
                                        {:field :f1 :before nil :after "updated"})]}))
          (is (= {:parent (uuid 1) :f1 nil #_"not 'updated'"}
                 (foreign-select-one [(keypath (uuid 40))
                                      (view select-keys-with-defaults [:parent :f1])]
                                     component-by-id))
              "Invalid parent fails the whole update => both parent and f1 remain as before")

          (testing "Component cannot be its own parent"
            (is (= {"component" {:error "Can't be ones own parent",
                                 :data {:parent (:_id grandparent1)}}}
                   (foreign-append! component-edits-depot
                                    (sut/map->ComponentEdits
                                      {:_id (:_id grandparent1)
                                       :edits [(sut/map->ComponentEdit
                                                 {:field :parent :before nil :after (:_id grandparent1)})]}))))
            (is (= nil
                   (foreign-select-one [(keypath (:_id grandparent1) :parent)] component-by-id))
                "The update should have failed, and :parent remained as-was, i.e. none"))

          (testing "No cycles in ancestors allowed"
            (is (= {"component" {:error "Ancestor loop",
                                 :data {:parent (:_id child)}}}
                   (foreign-append! component-edits-depot
                                    (sut/map->ComponentEdits
                                      {:_id (:_id grandparent1)
                                       :edits [(sut/map->ComponentEdit
                                                 {:field :parent :before nil :after (:_id child)})]}))))
            (is (= nil
                   (foreign-select-one [(keypath (:_id grandparent1) :parent)] component-by-id))
                "The update should have failed, and :parent remained as-was, i.e. none"))))
      (testing "cascading delete"
        ;; TODO
        ))))
