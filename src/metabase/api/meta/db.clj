(ns metabase.api.meta.db
  "/api/meta/db endpoints."
  (:require [compojure.core :refer [GET]]
            [korma.core :refer :all]
            [metabase.api.common :refer :all]
            [metabase.db :refer :all]
            (metabase.models [hydrate :refer [hydrate]]
                             [database :refer [Database]]
                             [table :refer [Table]])))

(defendpoint GET "/" [org]
  (sel :many Database :organization_id org (order :name)))

(defendpoint GET "/:id" [id]
  (->404 (sel :one Database :id id)
         (hydrate :organization)))

(defendpoint GET "/:id/tables" [id]
  (sel :many Table :db_id id (order :name)))

(define-routes)
