(ns mjolnir.ssa
  (:require [datomic.api :refer [q db] :as d]))

(def ^:dynamic *db-conn* nil)

(comment

  ssa-format
  [ARG_0 :ARG 0]
  [ARG_1 :ARG 1]
  [MUL_202 :MUL ARG_0 ARG_0]
  [MUL_203 :MUL ARG_1 ARG_1]
  [ADD_204 :ADD MUL_202 MUL_203]
  [FN_205 :GET-GLOBAL "llvm.sqrt"]
  [RET_205 :CALL FN_205 MUL_204]
  
  )

(def kw->attrs
  {:one [:db/cardinality :db.cardinality/one]
   :many [:db/cardinality :db.cardinalty/many]
   :ref [:db/valueType :db.type/ref]
   :keyword [:db/valueType :db.type/keyword]
   :int [:db/valueType :db.type/long]
   :string [:db/valueType :db.type/string]
   :unique [:db/unique :db.unique/value]})


(defn default-schema []
  {:list/tail #{:one :ref}
   :fn/type #{:one :ref}
   :fn/argument-names #{:one :ref}
   :fn/name #{:one :string}
   :fn/body #{:one :ref}

   :inst/block #{:one :ref}
   :inst/next #{:one :ref}
   :inst/type #{:one :keyword}
   

   :const/int-value #{:one :int}
   
   :argument/name #{:one :string}
   :argument/idx #{:one :int}

   :type.fn/return #{:one :ref}
   :type.fn/arguments #{:one :ref}

   :list/head #{:one :ref} 

   :node/type #{:one :keyword}
   :node/return-type #{:one :ref}
   
   :type/width #{:one :int}

   :type/element-type #{:one :ref}

   })

(defn debug [x]
  (doseq [v x]
    (println v))
  x)

(defn assert-schema [conn desc]
  (->> (for [[id attrs] desc]
         (merge
          {:db/id (d/tempid :db.part/db)
           :db/ident id
           :db.install/_attribute :db.part/db}
          (reduce
           (fn [m attr]
             (apply assoc m (kw->attrs attr)))
           {}
           attrs)))
       (d/transact conn)
       deref))

(defn get-query [sing]
  `[:find ~'?id
    :where
    ~@(map (fn [[k v]]
             (vector '?id k v))
           sing)])

(defn find-singleton [db sing]
  (println (get-query sing))
  (ffirst (q (get-query sing) db)))

(defrecord TxPlan [conn db singletons new-ents tempids])


(defn new-plan [conn]
  (->TxPlan conn (db conn) {} {} {}))

(defn commit [{:keys [conn db new-ents]}]
  (d/transact conn (map
                    (fn [[ent id]]
                      (assoc ent :db/id id))
                    new-ents)))

(defn plan-id 
  [plan val]
  (if-let [v (or (get-in plan [:singletons val])
                   (get-in plan [:new-ents val]))]
    v
    (assert false (str "Can't find " val))))

(defn singleton [plan sing]
  (if (get-in plan [:singletons sing])
    plan
    (if-let [q (find-singleton (:db plan) sing)]
      (assoc-in plan [:singletons sing] q)
      (let [newid (d/tempid :db.part/user)]
        (-> plan
            (assoc-in [:singletons sing] newid)
            (assoc-in [:new-ents sing] newid)
            (assoc-in [:tempids newid] nil))))))

(defn assert-entity [plan ent]
  (let [newid (d/tempid :db.part/user)]
        (-> plan
            (assoc-in [:new-ents ent] nil)
            (assoc-in [:tempids newid] nil))))


(defn transact-new [conn ent]
  (let [ent (if-not (:db/id ent)
              (assoc ent :db/id (d/tempid :db.part/user))
              ent)
        {:keys [db-after tempids]} @(d/transact conn [ent])
        tid (:db/id ent)]
    (->> (d/resolve-tempid db-after tempids tid)
         (d/entity db-after))))

(defn transact-singleton [conn sing]
  (let [genq (get-query sing)]
    (println genq "\n " sing "\n \n")
    (if-let [id (ffirst (q genq
                         (db conn)))]
      (d/entity (db conn) id)
      (transact-new conn sing))))

(defn transact-seq [conn seq]
  (reduce (fn [acc x]
            (transact-singleton
             conn
             (merge
              (if-let [id (:db/id acc)]
                {:list/tail id}
                {})
              {:list/head x})))
          {}
          (reverse seq)))


(defn new-block [conn fn]
  (transact-new ))


(defn to-seq [e]
  (when-not (nil? e)
    (cons (:list/head e)
          (lazy-seq (to-seq (:list/tail e))))))

(defn new-db []
  (let [url (str "datomic:mem://ssa" (name (gensym)))]
    (d/create-database url)
    (let [conn (d/connect url)]
      (assert-schema conn (default-schema))
      conn)))


(defprotocol IToPlan
  (-add-to-plan [this plan]
    "assert this item as datoms into the db and return the id of this entity"))

(defn add-to-plan [plan ent]
  (-add-to-plan ent plan))

#_(defn -main []
  (to-datomic-schema (default-schema))
  (println (transact-new conn {:node/type :type/int
                               :type.int/width 32}))
  (println (transact-singleton conn {:node/type :type/int
                                     :type.int/width 32})))