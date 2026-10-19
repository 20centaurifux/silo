(ns silo.transport.http.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [silo.transport.http.jetty :as jetty]
            [silo.transport.http.system :as http]))

(defn- server-config
  []
  {:binding {:host "127.0.0.1" :port 8443}
   :keystore {:path "service.p12" :password "key-secret"}
   :truststore {:path "truststore.p12" :password "trust-secret"}})

(deftest http-options-use-runtime-configuration-and-jetty-overrides
  (let [http-config (server-config)
        options {:host "127.0.0.1"
                 :ssl-port (get-in http-config [:binding :port])
                 :request-header-size 8192}]
    (doseq [[description config expected-options]
            [["without Jetty configuration"
              {:http {:server http-config}}
              options]
             ["with empty overrides"
              {:http {:server http-config} :jetty {:override {}}}
              options]
             ["with configured overrides"
              {:http {:server http-config}
               :jetty {:override {:request-header-size 16384}}}
              (assoc options :request-header-size 16384)]]]
      (testing description
        (with-redefs [jetty/->options (fn [actual-config]
                                        (is (= http-config actual-config))
                                        options)]
          (is (= expected-options
                 (ig/init-key ::http/opts {:config config}))))))))

(deftest integrant-resolves-http-dependencies-and-halts-the-started-server
  (let [init-key ig/init-key
        http-config (server-config)
        config {:http {:server http-config}}
        handler (fn [_] {:status 200 :body "healthy"})
        options {:host "127.0.0.1" :ssl-port (get-in http-config [:binding :port])}
        server (Object.)
        stopped? (atom false)]
    (with-redefs [ig/init-key (fn [key value]
                               ;; The application supplies these two components.
                                (case key
                                  :config value
                                  :http.server/handler value
                                  (init-key key value)))
                  jetty/->options (fn [actual-config]
                                    (is (= http-config actual-config))
                                    options)
                  jetty/start (fn [actual-handler actual-options]
                                (is (identical? handler actual-handler))
                                (is (= options actual-options))
                                server)
                  jetty/stop (fn [actual-server]
                               (is (identical? server actual-server))
                               (reset! stopped? true))]
      (let [system (ig/init (merge http/components
                                   {:config config
                                    :http.server/handler handler}))]
        (try
          (is (= options (::http/opts system)))
          (is (identical? server (:http/server system)))
          (is (false? @stopped?))
          (finally
            (ig/halt! system)))
        (is (true? @stopped?))))))