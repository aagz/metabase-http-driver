(ns metabase.driver.http
  "HTTP API driver."
  (:require [cheshire.core :as json]
            [metabase.driver :as driver]
            [metabase.models.metric :as metric :refer [Metric]]
            [metabase.driver.http.query-processor :as http.qp]
            [metabase.query-processor.store :as qp.store]
            [metabase.util :as u]
            [metabase.driver.http.parameters :as parameters]))

(defn find-first
  [f coll]
  (first (filter f coll)))

(defn- database->definitions
  [database]
  (json/parse-string (:definitions (:details database)) keyword))

(defn- database->table-defs
  [database]
  (or (:tables (database->definitions database)) []))

(defn- database->table-def
  [database name]
  (first (filter #(= (:name %) name) (database->table-defs database))))

(defn table-def->field
  [table-def name]
  (find-first #(= (:name %) name) (:fields table-def)))

(defn mbql-field->expression
  [table-def expr]
  (let [field (table-def->field table-def (:field-name expr))]
    (or (:expression field) (:name field))))

(defn mbql-aggregation->aggregation
  [table-def mbql-aggregation]
  (if (:field mbql-aggregation)
    [(:aggregation-type mbql-aggregation)
     (mbql-field->expression table-def (:field mbql-aggregation))]
    [(:aggregation-type mbql-aggregation)]))

(def json-type->base-type
  {:string  :type/Text
   :number  :type/Float
   :boolean :type/Boolean})

(driver/register! :http)

(defmethod driver/supports? [:http :basic-aggregations] [_ _] false)

(defmethod driver/can-connect? :http [_ _]
  true)

(defmethod driver/describe-database :http [_ database]
  (let [table-defs (database->table-defs database)]
    {:tables (set (for [table-def table-defs]
                    {:name   (:name table-def)
                     :schema (:schema table-def)}))}))

(defmethod driver/describe-table :http [_ database table]
(let [table-def  (database->table-def database (:name table))]
  {:name   (:name table-def)
    :schema (:schema table-def)
    :fields (set (for [[idx field] (map-indexed vector (:fields table-def))]
                  {:name          (:name field)
                    :database-type (:type field)
                    :base-type     (or (:base_type field)
                                      (json-type->base-type (keyword (:type field))))
                    :database-position idx}))}))

(defmethod driver/mbql->native :http [_ query]
  (let [database    (qp.store/database)
        table       (qp.store/table (:source-table (:query query)))
        table-def   (database->table-def database (:name table))
        breakout    (map (partial mbql-field->expression table-def) (:breakout (:query query)))
        aggregation (map (partial mbql-aggregation->aggregation table-def) (:aggregation (:query query)))]
    {:query (merge (select-keys table-def [:method :url :headers])
                   {:result (merge (:result table-def)
                                   {:breakout     breakout
                                    :aggregation  aggregation})})
     :mbql? true}))

(driver/register! :http)

(defmethod driver/supports? [:http :native-parameters] [_ _]                      true)

(defmethod driver/supports? [:http :foreign-keys] [_ _]                           false)
(defmethod driver/supports? [:http :nested-fields] [_ _]                          false)
(defmethod driver/supports? [:http :set-timezone] [_ _]                           false)
(defmethod driver/supports? [:http :basic-aggregations] [_ _]                     false)
(defmethod driver/supports? [:http :expressions] [_ _]                            false)
(defmethod driver/supports? [:http :expression-aggregations] [_ _]                false)
(defmethod driver/supports? [:http :nested-queries] [_ _]                         false)
(defmethod driver/supports? [:http :binning] [_ _]                                false)
(defmethod driver/supports? [:http :case-sensitivity-string-filter-options] [_ _] false)
(defmethod driver/supports? [:http :left-join] [_ _]                              false)
(defmethod driver/supports? [:http :right-join] [_ _]                             false)
(defmethod driver/supports? [:http :inner-join] [_ _]                             false)
(defmethod driver/supports? [:http :full-join] [_ _]                              false)

(defmethod driver/substitute-native-parameters :http
  [driver inner-query]
  (parameters/substitute-native-parameters driver inner-query))

(defmethod driver/execute-reducible-query :http [_ {native-query :native} _ respond]
  (http.qp/execute-http-request native-query respond))