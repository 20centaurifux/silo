(ns silo.transport.http.jetty
  (:require [clojure.java.io :as io]
            [meander.epsilon :as m]
            [ring.adapter.jetty :refer [run-jetty]])
  (:import [java.security KeyStore]))

(def ^:private protected-opts #{:host
                                :port
                                :ssl-port
                                :http?
                                :unix-socket
                                :configurator
                                :client-auth
                                :keystore
                                :key-password
                                :keystore-type
                                :truststore
                                :trust-password
                                :join?})

;;; Helpers

(defn- load-pkcs12
  ^KeyStore
  [path password]
  (let [store (KeyStore/getInstance "PKCS12")]
    (with-open [input (io/input-stream path)]
      (.load store input (char-array password)))
    store))

;;; Options

(defn ->options
  [config]
  (m/match config
    {:binding {:host ?host
               :port ?port}
     :keystore {:path ?keystore
                :password ?key-password}
     :truststore {:path ?truststore
                  :password ?trust-password}}
    {:host ?host
     :ssl-port ?port
     :http? false
     :client-auth :need
     :keystore (load-pkcs12 ?keystore ?key-password)
     :key-password ?key-password
     :truststore (load-pkcs12 ?truststore ?trust-password)
     :trust-password ?trust-password
     :join? false}))

(defn override-options
  [opts override]
  (let [override' (apply dissoc override protected-opts)]
    (merge opts override')))

;;; Lifecycle

(defn start
  [handler options]
  (run-jetty handler options))

(defn stop
  [s]
  (.stop s))