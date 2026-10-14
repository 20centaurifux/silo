(ns silo.transport.http.system
  (:require [integrant.core :as ig]
            [silo.transport.http.jetty :as impl]))

(def components {::opts {:config (ig/ref :config)}
                 :http/server {:handler (ig/ref :http.server/handler)
                               :opts (ig/ref ::opts)}})

(defmethod ig/init-key ::opts
  [_key {:keys [config]}]
  (-> (get-in config [:http :server])
      impl/->options
      (impl/override-options (get-in config [:jetty :override]))))

(defmethod ig/init-key :http/server
  [_key {:keys [handler opts]}]
  (impl/start handler opts))

(defmethod ig/halt-key! :http/server
  [_key server]
  (impl/stop server))