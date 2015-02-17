(ns metabase.query-processor.structured
  (:require [clojure.core.match :refer [match]]
            [metabase.db :refer [sel]]
            (metabase.models [hydrate :refer :all]
                             [database :refer [Database]]
                             [field :refer [Field]]
                             [table :refer [Table]])
            [metabase.util :refer [assoc*]]))

(defn annotate-column [table column-id]
  (let [{:keys [name base_type] :as column} (sel :one Field :id column-id)
        qualified-name (format "\"%s\".\"%s\"" (:name table) name)
        alias (match base_type
                "DateTimeField" (format "%s_date" name)
                :else name)
        format-str (match base_type
                     "DateTimeField" "DATE(%s) AS %s"
                     :else nil)
        select (if format-str (format format-str qualified-name alias)
                   qualified-name)]
    (assoc column
           :keyword (keyword name)
           :qualified-name qualified-name
           :alias alias
           :select select)))

(defn annotate-special-column [column]
  (println "COLUMN -> " column)
  (match column
    "count" {:name "Count"
             :keyword :count
             :alias :count
             :select "COUNT(*)"
             :base_type "IntegerField"
             :special_type "number"
             :table_id nil
             :extra_info nil
             :id nil
             :description nil}))

(defn get-columns [{:keys [table breakout aggregation] :as query}]
  (->> (concat breakout aggregation)
       (filter identity)
       (map (fn [column]
              (case (integer? column)
                true (annotate-column table column)
                false (annotate-special-column column))))))

(declare build-query
         generate-sql)

(defn process [{:keys [source_table] :as query}]
  (assoc* query
          :table (sel :one Table :id source_table)
          :database (:db (-> (:source_table <>)
                             (hydrate :db)))
          :columns ( get-columns <>)
          :columns-by-name (->> (:columns <>)
                                (map (fn [{:keys [keyword] :as col}]
                                       {keyword col}))
                                (reduce merge {}))
          :columns-by-id (->> (:columns <>)
                              (filter :id)
                              (map (fn [{:keys [id] :as col}]
                                     {id col}))
                              (reduce merge {}))
          :ordered-columns (map :keyword (:columns <>))
          :ordered-aliases (map #(keyword (:alias %)) (:columns <>))
          :sql (->> <>
                    build-query
                    generate-sql)))

(defn apply-breakout [query fields]
  (let [field-names (->> fields
                  (map (:columns-by-id query))
                  (map :alias)
                  (interpose ", ")
                  (apply str))]
    {:group-by field-names
     :order-by field-names}))

(defn apply-clause [query [clause-name clause-value]]
  (case clause-name
    :filter nil
    :breakout ( apply-breakout query clause-value)
    :limit (when clause-value
             {:limit clause-value})
    :aggregation nil))

(defn build-query [{:keys [table columns] :as query}]
  (let [q (->> (select-keys query [:filter :breakout :limit :aggregation])
               (map (partial apply-clause query))
               (filter identity)
               (apply merge {}))]
    (assoc q
           :select (->> (map :select columns)
                        (interpose ", ")
                        (apply str))
           :from (format "\"%s\"" (:name table)))))

(defn format-result [row]
  (->> row
       (map (fn [value]
              (if-not (= (type value) java.sql.Date) value
                      (.toString value))))))

(defn generate-sql [query]
  (clojure.pprint/pprint query)
  (letfn [(sqlwhen [kw sql-str]
            (let [val (kw query)]
              (when-not (empty? val) (str sql-str " " val))))]
    (->> [(sqlwhen :select "SELECT")
          (sqlwhen :from "FROM")
          (sqlwhen :group-by "GROUP BY")
          (sqlwhen :order-by "ORDER BY")
          (sqlwhen :limit "LIMIT")]
         (filter identity)
         (interpose "\n")
         (apply str))))

(defn process-and-run [{:keys [database] :as query}]
  (let [{:keys [sql columns ordered-columns columns-by-name] :as query-dict} (process (:query query))
        db (sel :one Database :id database)
        results ((:native-query db) sql)]
    {:status :completed
     :row_count (count results)
     :data {:rows (->> results
                       (map #(map % (:ordered-aliases query-dict)))
                       (map format-result))
            :columns (map :name columns)
            :cols (map columns-by-name
                       ordered-columns)}}))
