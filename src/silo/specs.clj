(ns silo.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.net Inet6Address InetAddress]))

;;; Common

(s/def ::non-blank-string
  (s/and string?
         (complement str/blank?)))

(s/def ::path ::non-blank-string)

(s/def ::password ::non-blank-string)

;;; Network

;; TODO: Replace InetAddress/getByName in both address predicates with
;; InetAddress/ofLiteral once Java 22 is the minimum supported version,
;; so validation does not perform DNS lookups.
(defn- ipv4-address?
  [value]
  (and (string? value)
       (try
         (instance? InetAddress (InetAddress/getByName value))
         (catch Exception _
           false))))

(defn- ipv6-address?
  [value]
  (and (string? value)
       (try
         (instance? Inet6Address (InetAddress/getByName value))
         (catch Exception _
           false))))

(defn- ip-address?
  [value]
  (or (ipv4-address? value)
      (ipv6-address? value)))

(s/def ::host (s/and ::non-blank-string ip-address?))

(s/def ::port #(and (int? %)
                    (<= 1 % 65535)))

(s/def ::binding (s/keys :req-un [::host ::port]))

;;; Keystore

(s/def ::keystore (s/keys :req-un [::path ::password]))

(s/def ::truststore (s/keys :req-un [::path ::password]))

;;; HTTP Options

(s/def :mtls.specs.http/server (s/keys :req-un [::binding
                                                ::keystore
                                                ::truststore]))

(s/def :mtls.specs/http (s/keys :req-un [:mtls.specs.http/server]))

;;; Config

(s/def ::config (s/keys :req-un [:mtls.specs/http]))