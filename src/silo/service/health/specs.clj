(ns silo.service.health.specs
  (:require [clojure.spec.alpha :as s]
            [silo.specs :as specs]))

(s/def ::health #{:healthy :degraded :unhealthy})

(s/def ::reason ::specs/non-blank-string)

(s/def ::status (s/keys :req-un [::health]
                        :opt-un [::reason]))

(s/def ::checks (s/map-of keyword? ::status))

(s/def ::health-status (s/keys :req-un [::health ::checks]))