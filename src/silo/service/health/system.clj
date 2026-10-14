(ns silo.service.health.system
  (:require [integrant.core :as ig]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [silo.service.health.api :as api]
            [silo.service.health.infrastructure.memory :as impl]))

(def components {:health/service {}
                 :health/routes {:service (ig/ref :health/service)}})

  ;;; Service

(defmethod ig/init-key :health/service
  [_key _value]
  (impl/service))

(defmethod ig/halt-key! :health/service
  [_key service]
  (api/shutdown! service))

  ;;; Routes

(defmethod ig/init-key :health/routes
  [_key {:keys [service]}]
  [["/health"
    {:muuntaja m/instance
     :middleware [muuntaja/format-middleware]
     :get (fn [_req]
            {:status 200
             :body (api/status service)})}]])