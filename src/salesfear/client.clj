(ns salesfear.client
  (:refer-clojure :exclude (get find update))
  (:use [clojure.string :only [join split escape]]
        [slingshot.slingshot :only [throw+ try+]])
  (:import (java.net URL)
           (com.palominolabs.crm.sf.core SObject
                                          Id)
           (com.palominolabs.crm.sf.soap ConnectionPoolImpl
                                         PartnerSObjectImpl)
           (com.palominolabs.crm.sf.rest RestConnectionPoolImpl)))

; Records
(defrecord CSObject [type id]
  SObject
  (getId [this] (Id. id))
  (getType [this] type)
  (getField [this k] (this (keyword k)))
  (isFieldSet [this k] (contains? this (keyword k)))
  (getAllFields [this] (into {} (for [[k v] this]
                                  (when-not (or (= :type k)
                                                (= :id k))
                                    [(name k) v]))))
  ; Fuck mutability
  (setField [this k v] (throw RuntimeException))
  (setAllFields [this m] (throw RuntimeException))
  (removeField [this k] (throw RuntimeException)))

(defn sobject
  "Creates an immutable CSObject. Can take an SObject, or a type, id, and map
  of fields to values. Ids will be converted to Id objects, types will be
  converted to strings. (sobject nil) is nil."
  ([type id fields]
   (let [id (if (instance? Id id) (str id) id)]
     (merge (CSObject. (name type) id)
            fields)))
  ([^SObject sobject]
   (if-not sobject
     nil
     (into
       (CSObject. (.getType sobject) (str (.getId sobject)))
       (for [[k v] (.getAllFields sobject)]
         [(keyword k) v])))))

; Misc stuff
(def client-id (com.codahale.metrics.MetricRegistry.))
(def cache-default {:sobject-field-names {}})
(def ^:dynamic cache (atom cache-default))

; Pools
(def ^:dynamic *rest-pool*)
(def ^:dynamic *soap-pool*)
(def ^:dynamic *org-id*)
(def ^:dynamic *sandbox*)

(defn soap-pool
  "Returns a SOAP connection pool."
  [org-id username password threads is-sandbox]
  (if-not is-sandbox
    (doto (ConnectionPoolImpl. "" client-id)
      (.configureOrg org-id username password threads))
    (doto (ConnectionPoolImpl. "" client-id)
      (.configureSandboxOrg org-id username password threads))))

(defn rest-pool
  "Returns a REST connection pool from a soap pool"
  [soap-pool org-id is-sandbox]
  (if-not is-sandbox
    (let [bc (.getBindingConfig (.getConnectionBundle soap-pool org-id))
          host (.getHost (URL. (.getPartnerServerUrl bc)))
          token (.getSessionId bc)]
      (doto (RestConnectionPoolImpl. client-id)
        (.configureOrg org-id host token)))
    (let [bc (.getBindingConfig (.getSandboxConnectionBundle soap-pool org-id))
          host (.getHost (URL. (.getPartnerServerUrl bc)))
          token (.getSessionId bc)]
      (doto (RestConnectionPoolImpl. client-id)
        (.configureOrg org-id host token)))))

(defmacro salesforce
  "Executes exprs with implicit credentials, organization, etc. Opts:
  :username  SF username
  :password  SF password
  :orgid     Organization ID
  :threads   Maximum number of concurrent operations"
  [opts & exprs]
  `(let [soap-pool# (soap-pool ~(:org-id opts)
                               ~(:username opts)
                               ~(:password opts)
                               ~(or (:threads opts) 8)
                               ~(or (:is-sandbox opts) false))
         rest-pool# (rest-pool soap-pool# ~(:org-id opts) ~(or (:is-sandbox opts) false))]
     (binding [cache       (atom cache-default)
               *soap-pool* soap-pool#
               *rest-pool* rest-pool#
               *org-id*    ~(:org-id opts)
               *sandbox* ~(:is-sandbox opts)]
       ~@exprs)))

(defn salesforce!
  "Like (salesforce), but redefines local vars destructively. Useful for REPL
  testing."
  [opts]
  (let [soap-pool (soap-pool (:org-id opts)
                             (:username opts)
                             (:password opts)
                             (or (:threads opts) 8)
                             (or (:is-sandbox opts) false))
        rest-pool (rest-pool soap-pool (:org-id opts) (or (:is-sandbox opts) false))]
    (def ^:dynamic cache (atom cache-default))
    (def ^:dynamic *soap-pool* soap-pool)
    (def ^:dynamic *rest-pool* rest-pool)
    (def ^:dynamic *org-id* (:org-id opts))
    (def ^:dynamic *sandbox* (:is-sandbox opts))))

(defn soap-conn []
  (if *sandbox*
    (.getPartnerConnection (.getSandboxConnectionBundle *soap-pool* *org-id*))
    (.getPartnerConnection (.getConnectionBundle *soap-pool* *org-id*))))

(defn rest-conn []
  (.getRestConnection *rest-pool* *org-id*))

; Meat n potatoes
(defn describe-global*
  []
  "Returns the raw GlobalSObjectDescription."
  (.describeGlobal (rest-conn)))

(defn describe-global
  "Returns a list of beans corresponding to global sobject descriptions."
  []
  (map bean (.getBasicSObjectMetadatas (describe-global*))))

(defn describe-sobject*
  "Returns a java description of the given type of sobject."
  [type]
  (.describeSObject (rest-conn) (name type)))

(defn describe-sobject
  "Returns a map describing the given type of sobject. Lots of beans here.
  Definitely wouldn't depend on this structure having this shape in the future.
  Use describe-sobject* if you can. :-/"
  [type]
  (let [b (bean (describe-sobject* type))]
    (merge (dissoc b :fields :childRelationships :recordTypeInfos)
      {:fields              (map bean (:fields b))
       :childRelationships  (map bean (:childRelationships b))
       :recordTypeInfos     (map bean (:recordTypeInfos b))})))

(defn soql-quote
  "Single-quotes a string. escaping single-quotes
  within."
  [string]
  (str "'" (escape string {\' "\\'"}) "'"))

(defn soql-literal
  "Returns SOQL string fragments for values. Strings are quoted, keywords are
  not quoted, numbers are converted with (str), true, false, and nil are
  \"true\", \"false\", and \"null\".

  I haven't actually verified soql's syntax, so this could bite you. :-D"
  ([x]
   (cond (nil? x) "null"
         (true? x) "true"
         (false? x) "false"
         (keyword? x) (name x)
         (string? x) (soql-quote x)
         (number? x) (str x)
         (instance? java.util.Date x) (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") x)
         true (str x)))
  ([x _]
   (cond (nil? x) "null"
         (true? x) "true"
         (false? x) "false"
         (keyword? x) (name x)
         ;; (string? x) (soql-quote x)
         (number? x) (str x)
         (instance? java.util.Date x) (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") x)
         true (str x))))

(defn soql-where
  "Given a map of fields to values, returns a string like field1 = \"value1\" AND field2 = 2.4"
  [constraints]
  (join " and " (map (fn [[k v]] (str (soql-literal k) " = " (soql-literal v)))
                     constraints)))

(defn soql
  "Constructs a soql query string. Passes through strings unchanged. With
  vectors like [\"email = ?\" \"foo\"], replaces ? with corresponding strings
  from the remainder of the vector, quoted."
  [q]
  (if (string? q)
    q
    (let [[string & values] q
          fragments         (split string #"\?" (inc (count values)))
          ; We'll have one more fragment than value, so prepend an initial "".
          values            (concat [""] (map soql-literal values))]
      (assert (= (count fragments) (count values)))
      (apply str (interleave values fragments)))))

(defn query
  "Returns a collection of SOBjects matching the given query. e.g:
  (query [\"SELECT id, name, email from Lead where Email = ?\" \"foo@bar.com\"])
  (query \"SELECT id from Lead where Email = 'foo@bar.com'\")"
  [q]
  (let [res (-> (rest-conn) (.query (soql q)))
        sobjects (map sobject (.getSObjects res))]
    (vary-meta sobjects merge {:done (.isDone res)
                               :total (.getTotalSize res)
                               :query-locator (.getQueryLocator res)})))

(defn sobject-field-names
  "Returns a seq of field names on an sobject type."
  [type]
  (let [type (name type)]
    (or ; Cached
        (get-in @cache [:sobject-field-names type])
        ; Uncached
        (let [names (map #(.getName %) (.getFields (describe-sobject* type)))]
          (swap! cache assoc-in [:sobject-field-names type] names)
          names))))

(defn create
  "Creates an sobject. Returns id if created, throws errors. Might return false
  if not successful, whenever that happens."
  ([type fields] (create type nil fields))
  ([type id fields] (create (sobject type id
                                     (persistent!
                                      (reduce (fn [m [k v]] (assoc! m k (soql-literal v nil)))
                                              (transient (empty {})) fields)))))
  ([sobject]
  (let [res (.create (rest-conn) sobject)]
        (if (.isSuccess res)
          (str (.getId res))
          false))))

(defn creates
  ([type fields-seq] (creates type nil fields-seq))
  ([type id fields-seq]
   (creates (mapv (fn [fs]
                    (doto (PartnerSObjectImpl/getNew (name type))
                      (.setAllFields (persistent!
                                      (reduce (fn [m [k v]] (assoc! m (name k) (soql-literal v nil)))
                                              (transient (empty {})) fs)))))
                 fields-seq)))
  ([sobjects]
   (let [res (mapcat #(.create (soap-conn) %)
                  (partition-all 200 (java.util.ArrayList. sobjects)))]
     (for [r res]
       (if (.isSuccess r)
         (str (.getId r))
         false)))))

(defn delete
  "Delete an sobject of type with the given id, or, deletes an sobject. Returns
  :deleted or :already-deleted."
  ([sobject]
   (assert (instance? SObject sobject))
   (assert (and (:type sobject) (:id sobject)))
   (delete (:type sobject) (:id sobject)))
  ([type id]
   (let [id (if (string? id) (Id. id) id)]
     (try+
       (.delete (rest-conn) (name type) id)
       :deleted
       (catch (and (instance? com.palominolabs.crm.sf.rest.ApiException
                              (.getCause %))
                   (= 404 (.getHttpResponseCode (.getCause %)))) e
         :already-deleted)))))

(defn find
  "Finds SObjects of type given constraints, an (inline) map of fields to
  values, which are implicitly ANDed together. No support for pagination, so if
  you blow the limit, too bad. Example:

  (find :Account) ; all accounts
  (find :Account {:id \"1234\"})
  (find :Account {:Name \"Joe\" :age 32)}"
  ([type]
   (query (str "select " (join ", "(sobject-field-names type))
               " from " (soql-literal type))))
  ([type constraints]
   (query (str "select " (join ", "(sobject-field-names type))
               " from " (soql-literal type)
               " where " (soql-where constraints)))))

(defn ffind
  "Like find, but yields only one result."
  [type constraints]
  (first (query (str "select " (join ", " (sobject-field-names type))
                     " from " (soql-literal type)
                     " where " (soql-where constraints)
                     " limit 1"))))

(defn get
  "Gets a single SObject by type and ID. If fields is omitted, gets all
  fields. If the object does not exist, returns nil."
  ([type id] (get type id (sobject-field-names type)))
  ([type id fields]
   (let [id (if (string? id) (Id. id) id)]
     (try+
       (sobject (.retrieve (rest-conn) (name type) id fields))
       (catch (and (instance? com.palominolabs.crm.sf.rest.ApiException
                              (.getCause %))
                   (= 404 (.getHttpResponseCode (.getCause %)))) e)))))

(def retrieve get)

(defn update
  "Updates an sobject. Returns sobject."
  ([type id fields]
   (update (sobject type id (persistent! (reduce (fn [m [k v]] (assoc! m k (soql-literal v nil))) (transient (empty {})) fields)))))
  ([sobject]
   (.update (rest-conn) sobject)
   sobject))

(defn upsert
  "Upserts an sobject, given an sobject and the field name of the external id
  field."
  [sobject external-id-field]
  (.upsert (rest-conn) sobject external-id-field))

(defmacro with-prod
  [exprs]
  `(salesforce {:org-id ""
                :username ""
                :password ""}
               ~exprs))

(defmacro with-sandbox
  [exprs]
  `(salesforce {:org-id ""
                :username ""
                :password ""
                :is-sandbox true}
               ~exprs))
