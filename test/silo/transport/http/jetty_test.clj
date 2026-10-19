(ns silo.transport.http.jetty-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as ring-jetty]
            [silo.transport.http.jetty :as jetty])
  (:import [java.io FileNotFoundException IOException]
           [java.net InetSocketAddress SocketException]
           [java.nio.file Files]
           [java.security KeyStore]
           [java.security.cert X509Certificate]
           [java.util.concurrent TimeUnit]
           [javax.crypto.spec SecretKeySpec]
           [javax.net.ssl KeyManager SSLContext SSLHandshakeException SSLSocket
            TrustManagerFactory X509KeyManager]
           [org.eclipse.jetty.server Server ServerConnector]))

(defn- with-temp-directory
  [f]
  (let [directory (.toFile (Files/createTempDirectory
                            "silo-jetty-test"
                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f directory)
      (finally
        (doseq [file (reverse (file-seq directory))]
          (io/delete-file file))))))

(defn- empty-pkcs12
  []
  (doto (KeyStore/getInstance "PKCS12")
    (.load nil nil)))

(defn- save-pkcs12!
  [store file password]
  (with-open [output (io/output-stream file)]
    (.store ^KeyStore store output (char-array password))))

(defn- write-pkcs12-with-key!
  [file password alias]
  (let [store (empty-pkcs12)]
    (.setKeyEntry store
                  alias
                  (SecretKeySpec. (byte-array (range 16)) "AES")
                  (char-array password) nil)
    (save-pkcs12! store file password)))

(defn- binding-port
  [default-port]
  (if-let [value (System/getenv "HTTP_PORT")]
    (Integer/parseInt value)
    default-port))

(defn- fixture-config
  [directory]
  {:binding {:host "127.0.0.1"
             :port (binding-port 8443)}
   :keystore {:path (.getPath (io/file directory "service.p12"))
              :password "key-secret"}
   :truststore {:path (.getPath (io/file directory "truststore.p12"))
                :password "trust-secret"}})

(defn- write-option-stores!
  [config]
  (doseq [[store alias] [[:keystore "server-key"]
                         [:truststore "trusted-key"]]]
    (let [{:keys [path password]} (get config store)]
      (write-pkcs12-with-key! path password alias))))

(deftest options-load-pkcs12-stores-and-require-client-certificates
  (with-temp-directory
    (fn [directory]
      (let [config (fixture-config directory)]
        (write-option-stores! config)
        (let [options (jetty/->options config)]
          (is (= {:host "127.0.0.1"
                  :ssl-port (get-in config [:binding :port])
                  :http? false
                  :client-auth :need
                  :key-password "key-secret"
                  :trust-password "trust-secret"
                  :join? false}
                 (dissoc options :keystore :truststore)))
          (doseq [[store alias] [[:keystore "server-key"]
                                 [:truststore "trusted-key"]]]
            (testing (name store)
              (let [loaded (get options store)]
                (is (instance? KeyStore loaded))
                (is (= "PKCS12" (.getType ^KeyStore loaded)))
                (is (= #{alias} (set (enumeration-seq (.aliases ^KeyStore loaded)))))))))))))

(deftest options-reject-missing-corrupt-and-incorrectly-unlocked-stores
  (doseq [store [:keystore :truststore]
          failure [:missing :corrupt :wrong-password]]
    (testing (str (name store) ": " (name failure))
      (with-temp-directory
        (fn [directory]
          (let [config (fixture-config directory)
                path (get-in config [store :path])]
            (write-option-stores! config)
            (case failure
              :missing (io/delete-file path)
              :corrupt (spit path "not a PKCS12 store")
              :wrong-password nil)
            (let [config' (if (= failure :wrong-password)
                            (assoc-in config [store :password] "wrong-secret")
                            config)]
              (if (= failure :missing)
                (is (thrown? FileNotFoundException (jetty/->options config')))
                (is (thrown? IOException (jetty/->options config')))))))))))

(deftest override-options-cannot-replace-protected-settings
  (let [options {:host "127.0.0.1"
                 :port 8080
                 :ssl-port 8443
                 :http? false
                 :unix-socket "/tmp/server.sock"
                 :configurator :original-configurator
                 :client-auth :need
                 :keystore :original-keystore
                 :key-password "original-key-password"
                 :keystore-type "PKCS12"
                 :truststore :original-truststore
                 :trust-password "original-trust-password"
                 :join? false
                 :custom-option :original}
        override (merge (zipmap [:host
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
                                 :join?]
                                (repeat :overridden))
                        {:custom-option :overridden
                         :request-header-size 8192})]
    (is (= (assoc options
                  :custom-option :overridden
                  :request-header-size 8192)
           (jetty/override-options options override)))))

(deftest override-options-cannot-add-protected-settings
  (doseq [option [:host :port :ssl-port :http? :unix-socket :configurator
                  :client-auth :keystore :key-password :keystore-type
                  :truststore :trust-password :join?]]
    (testing (name option)
      (is (= {:custom-option :original
              :request-header-size 8192}
             (jetty/override-options {:custom-option :original}
                                     {option :overridden
                                      :request-header-size 8192}))))))

(deftest absent-and-empty-overrides-preserve-options
  (let [options {:host "127.0.0.1"
                 :ssl-port 8443
                 :http? false
                 :client-auth :need
                 :join? false
                 :custom-option :original}]
    (doseq [override [nil {}]]
      (testing (pr-str override)
        (is (= options (jetty/override-options options override)))))))

(deftest start-passes-handler-and-options-to-jetty
  (let [handler (fn [_] {:status 200})
        options {:host "127.0.0.1"}
        server (Object.)]
    (with-redefs [ring-jetty/run-jetty (fn [actual-handler actual-options]
                                         (is (identical? handler actual-handler))
                                         (is (= options actual-options))
                                         server)]
      (is (identical? server (jetty/start handler options))))))

(deftest stop-stops-the-jetty-server
  (let [server (Server.)]
    (try
      (.start server)
      (jetty/stop server)
      (is (.isStopped server))
      (finally
        (when-not (.isStopped server)
          (.stop server))))))

(defn- generate-identity!
  [file password alias usage]
  (let [keytool (.getPath (io/file (System/getProperty "java.home") "bin"
                                   (if (str/starts-with? (System/getProperty "os.name") "Windows")
                                     "keytool.exe"
                                     "keytool")))
        command [keytool "-genkeypair" "-noprompt"
                 "-storetype" "PKCS12" "-keystore" (.getPath file)
                 "-storepass" password "-keypass" password
                 "-alias" alias "-dname" (str "CN=" alias)
                 "-keyalg" "RSA" "-keysize" "2048" "-validity" "2"
                 "-ext" "SAN=DNS:localhost,IP:127.0.0.1"
                 "-ext" (str "EKU=" usage)]
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream true)))]
    (with-open [output (.getInputStream process)]
      (when-not (.waitFor process 30 TimeUnit/SECONDS)
        (.destroyForcibly process)
        (throw (ex-info "Timed out generating test certificate" {:alias alias})))
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Failed to generate test certificate"
                        {:alias alias :output (slurp output)}))))
    (KeyStore/getInstance file (char-array password))))

(defn- client-key-manager
  [^KeyStore store password alias]
  ;; Always offer this identity, even when the server's trusted issuers differ.
  ;; This makes the foreign-certificate case test rejection, not omission.
  (reify X509KeyManager
    (getClientAliases [_ _ _] (into-array String [alias]))
    (chooseClientAlias [_ _ _ _] alias)
    (getServerAliases [_ _ _] nil)
    (chooseServerAlias [_ _ _ _] nil)
    (getCertificateChain [_ _]
      (into-array X509Certificate (.getCertificateChain store alias)))
    (getPrivateKey [_ _] (.getKey store alias (char-array password)))))

(defn- client-context
  [trusted-server key-manager]
  (let [truststore (empty-pkcs12)
        managers (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
        context (SSLContext/getInstance "TLS")]
    (.setCertificateEntry truststore "server" trusted-server)
    (.init managers truststore)
    ;; An empty array explicitly disables client credentials for the no-cert case.
    (.init context (into-array KeyManager (if key-manager [key-manager] []))
           (.getTrustManagers managers) nil)
    context))

(defn- with-client-socket
  [^SSLContext context port f]
  (with-open [^SSLSocket socket (.createSocket (.getSocketFactory context))]
    (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 5000)
    (.setSoTimeout socket 5000)
    ;; Keep certificate rejection in the initial handshake with TLS 1.2.
    (.setEnabledProtocols socket (into-array String ["TLSv1.2"]))
    (.setSSLParameters socket (doto (.getSSLParameters socket)
                                (.setEndpointIdentificationAlgorithm "HTTPS")))
    (f socket)))

(defn- https-request
  [context port]
  (with-client-socket
    context port
    (fn [^SSLSocket socket]
      (.startHandshake socket)
      (let [writer (io/writer (.getOutputStream socket) :encoding "US-ASCII")]
        (.write writer "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        (.flush writer))
      (slurp (.getInputStream socket) :encoding "UTF-8"))))

(defn- handshake-rejected?
  [context port]
  (with-client-socket
    context port
    (fn [^SSLSocket socket]
      ;; The peer can close the connection before Java reads its TLS alert.
      ;; Catch only handshake failures; connect errors and timeouts propagate.
      (try
        (.startHandshake socket)
        false
        (catch SSLHandshakeException _ true)
        (catch SocketException _ true)))))

(deftest mtls-accepts-trusted-client-and-rejects-missing-or-foreign-certificates
  (with-temp-directory
    (fn [directory]
      (let [config (assoc-in (fixture-config directory) [:binding :port] (binding-port 0))
            client-password "client-secret"
            server-store (generate-identity! (io/file (get-in config [:keystore :path]))
                                             (get-in config [:keystore :password])
                                             "server" "serverAuth")
            client-store (generate-identity! (io/file directory "client.p12")
                                             client-password "client" "clientAuth")
            foreign-store (generate-identity! (io/file directory "foreign.p12")
                                              client-password "foreign" "clientAuth")
            truststore (empty-pkcs12)
            server-certificate (.getCertificate server-store "server")
            requests (atom 0)
            handler (fn [_]
                      (swap! requests inc)
                      {:status 200
                       :headers {"Content-Type" "text/plain"
                                 "Content-Length" "7"}
                       :body "healthy"})]
        (.setCertificateEntry truststore "client" (.getCertificate client-store "client"))
        (save-pkcs12! truststore (get-in config [:truststore :path])
                      (get-in config [:truststore :password]))
        (let [server (jetty/start handler (jetty/->options config))]
          (try
            (is (= 1 (count (.getConnectors server))))
            (let [port (.getLocalPort ^ServerConnector (first (.getConnectors server)))]
              (testing "trusted client reaches the handler over HTTPS"
                (let [context (client-context server-certificate
                                              (client-key-manager client-store client-password "client"))
                      response (https-request context port)]
                  (is (str/starts-with? response "HTTP/1.1 200 "))
                  (is (str/ends-with? response "\r\n\r\nhealthy"))
                  (is (= 1 @requests))))
              (testing "client without a certificate is rejected during the TLS handshake"
                (is (handshake-rejected? (client-context server-certificate nil) port))
                (is (= 1 @requests)))
              (testing "client with an untrusted certificate is rejected during the TLS handshake"
                (let [context (client-context server-certificate
                                              (client-key-manager foreign-store client-password "foreign"))]
                  (is (handshake-rejected? context port))
                  (is (= 1 @requests)))))
            (finally
              (jetty/stop server))))))))