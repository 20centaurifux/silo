(ns silo.service.health.infrastructure.memory
  (:require [clojure.spec.alpha :as s]
            [silo.service.health.api :as api]
            [silo.service.health.specs :as specs]))

(defn- overall-health
  [checks]
  (cond
    (some #(= :unhealthy (:health %)) (vals checks)) :unhealthy
    (some #(= :degraded (:health %)) (vals checks)) :degraded
    :else :healthy))

(defn- health-status
  [checks]
  {:health (overall-health checks)
   :checks checks})

(defn- validate-check!
  [k status]
  (when-not (keyword? k)
    (throw (ex-info "Invalid check key" {:key k})))

  (when-not (s/valid? ::specs/status status)
    (throw (ex-info "Invalid check status"
                    {:key k
                     :status status}))))

(deftype Service [checks]
  api/Service
  (status [_]
    (health-status @checks))

  (add-check! [_ k status]
    (validate-check! k status)
    (-> (swap! checks assoc k status)
        health-status))

  (shutdown! [_]))

(alter-meta! #'->Service assoc :private true)

(defn service
  []
  (->Service (atom {})))