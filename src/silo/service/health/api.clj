(ns silo.service.health.api
  (:require [clojure.spec.alpha :as s]
            [silo.service.health.specs :as specs]))

;;; Status

(defn- ->status
  ([health]
   (when-not (s/valid? ::specs/health health)
     (throw (ex-info "Invalid health" {:health health})))

   {:health health})
  ([health reason]
   (when-not (s/valid? ::specs/health health)
     (throw (ex-info "Invalid health status" {:health health})))

   (when-not (s/valid? ::specs/reason reason)
     (throw (ex-info "Invalid reason" {:health health
                                       :reason reason})))

   {:health health
    :reason reason}))

(defn healthy
  []
  (->status :healthy))

(defn degraded
  ([]
   (->status :degraded))
  ([reason]
   (->status :degraded reason)))

(defn unhealthy
  ([]
   (->status :unhealthy))
  ([reason]
   (->status :unhealthy reason)))

;;; Protocol

(defprotocol Service
  (status [service])
  (add-check! [service k status])
  (shutdown! [service]))