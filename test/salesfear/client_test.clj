(ns salesfear.client-test
  (:refer-clojure :exclude (get find))
  (:use clojure.test
        salesfear.client))

(def user "kyle@boundary.com")
(def pass (str "blah" "blah"))
(def org-id "00DE0000000b894")

(defn find-first 
  ([field value coll]
   (find-first #(= value (field %)) coll))
  ([pred coll]
    (first (filter pred coll))))

(defmacro sf
  [& exprs]
  `(salesforce {:username ~user
                :password ~pass
                :org-id ~org-id}
               ~@exprs))

(defn clear-accounts []
  (doseq [a (find :Account)]
    (try (delete a) (catch Exception e))))

(deftest 
  client-test

  (sf
    (clear-accounts)
    (testing "describe-global"
             (let [d (describe-global)
                   a (find-first :name "Account" d)]
               (is (seq? d))
               (is a)
               (is (= "Accounts" (:labelPlural a)))
               (is (:queryable a))))

    (testing "describe-sobject"
             (let [d (describe-sobject :Account)]
               (is (= "Accounts" (:labelPlural d)))
               (is (= true (:queryable d)))
               (is (= "ParentId" 
                      (:field (first (:childRelationships d)))))))

    (testing "sobject"
             (let [so (sobject :Account "123" {:foo "hey"})]
               (is (= "Account" (:type so)))
               (is (= "123" (:id so)))
               (is (= "hey" (:foo so)))))

    (testing "create"
             (let [id (create :Account {:Name "create"})]
               (is (string? id))
               (is (= "create" (:Name (get :Account id))))
               (delete :Account id)))

    (testing "get"
             (let [id (create (sobject :Account nil {:Name "get"}))
                   a  (get :Account id)]
               (is (= id (:id a)))
               (is (= "get" (:Name a)))
               (delete :Account id)))

    (testing "update"
             (let [id (create (sobject :Account nil {:Name "update"}))
                   r  (update (sobject :Account id  {:Name "update2"}))]
               (is (= id (:id r)))
               (is (= "update2" (:Name (get :Account id))))
               (delete :Account id)))

    (testing "delete"
             (let [id (create :Account {:Name "delete"})]
               (is (= :deleted (delete :Account id)))
               (is (= :already-deleted (delete :Account id)))
               (is (nil? (get :Account id)))))

    (testing "query"
             (let [id (create (sobject :Account nil {:Name "Toh'Kaht"}))
                   r1 (query "select id from Account where name = 'Toh\\'Kaht'")

                   r2 (query ["select id, name from ? where name = ?"
                              :Account "Toh'Kaht"])]
               (is (some #{id} (map :id r1)))
               (is (some #{id} (map :id r2)))
               (is (every? nil? (map :Name r1)))
               (is (every? (partial = "Toh'Kaht") (map :Name r2)))
               (delete :Account id)))

    (testing "find"
             (let [id (create :Account nil {:Name "on'e" :Site "yo"})]
               (is (= id (:id (ffind :Account {:Name "on'e" :Site "yo"}))))
               (is (= "yo" (:Site (first (find :Account {:Name "on'e"})))))
               (delete :Account id)))
))
