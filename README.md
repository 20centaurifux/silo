# silo

`silo` is a foundation for HTTP microservices in Clojure. Services communicate
via HTTPS with mandatory client certificates (mTLS). Integrant connects the
components and manages their lifecycle.

![silo logo](doc/silo.png)

`silo` is currently under development.

## Creating the example certificates

Before starting the HTTP example below, generate its local mTLS certificates
with the Makefile in `certs`. You need `make`, OpenSSL with support for
`openssl req -addext`, and the JDK's `keytool` on your `PATH`.
Run this command from the project root:

```sh
make -C certs
```

The default target creates a test certificate authority (CA), then uses it to
sign the service and client certificates:

| Generated files | Purpose |
| --- | --- |
| `certs/ca/ca.crt`, `certs/ca/ca.key` | Test CA certificate and private key. |
| `certs/ca/truststore.p12` | PKCS#12 truststore containing the test CA, used by the server to trust client certificates. |
| `certs/service/service.crt`, `certs/service/service.key`, `certs/service/service.p12` | Service certificate, private key, and PKCS#12 keystore for the HTTPS server. |
| `certs/client/client.crt`, `certs/client/client.key`, `certs/client/client.p12` | Client certificate, private key, and PKCS#12 keystore for mTLS requests. |

All PKCS#12 stores use the example password `trustno1`, matching the
configuration below. The CA certificate is valid for 3650 days; service and
client certificates are valid for 90 days. These credentials are for local
development only.

To regenerate them, first remove the generated directories, then build again:

```sh
make -C certs clean
make -C certs
```

This replaces the CA and all service/client keys and certificates. Restart any
running services so they load the new stores. Cleaning first also avoids
importing the same CA alias into an existing truststore.

With the HTTPS example running on port 8443, test an authenticated request:

```sh
curl --resolve service.local:8443:127.0.0.1 \
     --cacert certs/ca/ca.crt \
     --cert certs/client/client.crt \
     --key certs/client/client.key \
     https://service.local:8443/
```

The service certificate includes `service.local` as a DNS Subject Alternative
Name in `certs/service.ext`. `--resolve` connects that hostname to localhost
while allowing curl to verify the certificate's hostname.

## Architecture

### HTTP transport and Integrant

![silo logo](doc/transport.jpg)

To use `silo.transport.http`, the application requires
`silo.transport.http.system` and merges its `components` map into the application's
Integrant configuration. It supplies initialization methods for two keys:

| Component key | What the application must provide |
| --- | --- |
| `:config` | A runtime configuration map with the HTTP settings shown below. The application loads and validates this configuration. |
| `:http.server/handler` | A Ring handler that combines the application's routes, middleware, and fallback responses. |

Route collection and router creation belong to the application. Keys such as
`::routes` and `::router` in the example below are application-defined; the
transport requires only the two application keys listed above.

For example, this application serves `Hello, world!` at `GET /`:

```clojure
(ns example.core
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [silo.transport.http.system :as http]))

;; Integrant calls this method to create the application's runtime settings.
;; A real application can load settings externally and validate them against
;; :silo.specs/config before returning them.
(defmethod ig/init-key :config
  [_ _]
  {:http {:server {:binding {:host "127.0.0.1"
                             :port 8443}
                   ;; The PKCS#12 keystore contains the server's certificate and
                   ;; private key, used to identify the server TLS setup.
                   ;; Relative paths are resolved from the process working
                   ;; directory; run this example from the project root.
                   ;; These fixed passwords are for the local example. Supply
                   ;; deployment credentials through external configuration.
                   :keystore {:path "./certs/service/service.p12"
                              :password "trustno1"}
                   ;; The PKCS#12 truststore contains certificates trusted when
                   ;; authenticating clients. The transport requires a trusted
                   ;; client certificate (mutual TLS), even for the root route.
                   ;; Its password unlocks the truststore.
                   :truststore {:path "certs/ca/truststore.p12"
                                :password "trustno1"}}}})

(defmethod ig/init-key ::routes
  [_ _]
  [["/" {:get (fn [_request]
                ;; A Ring handler receives a request map and returns a response
                ;; map. This handler ignores the request and always returns a
                ;; successful plain-text response with an explicit encoding.
                {:status 200
                 :headers {"content-type" "text/plain; charset=utf-8"}
                 :body "Hello, world!"})}]])

;; Integrant resolves the routes reference before invoking this initializer.
;; Reitit compiles that route data into the router used for request matching.
(defmethod ig/init-key ::router
  [_ {:keys [routes]}]
  (ring/router routes))

;; Convert the router into a Ring handler and provide Reitit's default responses
;; for unmatched requests, including unknown paths and unsupported methods.
;; Application middleware can be added here around the resulting handler.
(defmethod ig/init-key :http.server/handler
  [_ {:keys [router]}]
  (ring/ring-handler router (ring/create-default-handler)))

(defn components
  []
  (merge {:config {}
          ::routes {}
          ::router {:routes (ig/ref ::routes)}
          :http.server/handler {:router (ig/ref ::router)}}
         ;; The transport adds its options and server components. They depend on
         ;; :config and :http.server/handler supplied above.
         http/components))

;; Start the full dependency graph and retain the initialized component map.
;; Evaluating this form (including when the namespace is first loaded) opens
;; the HTTPS listener and loads the certificate stores from disk. Jetty starts
;; without blocking this thread because the transport sets :join? to false.
;; Stop the running components with (ig/halt! system). Halt the existing system
;; before evaluating this definition again to release the listening port.
(def system (ig/init (components)))

;; (ig/halt! system)
```

### HTTP middleware

The application installs middleware on its Ring handler or shared routes and
defines any authorization rules. To use the supplied certificate middleware,
require `silo.transport.http.middleware` as `middleware` and wrap the handler:

```clojure
(-> handler
    middleware/wrap-certificate-validity-period
    middleware/wrap-common-name)
```

This order checks that a certificate with a Common Name is present before
checking its validity period. Handlers can use `:common-name` from the request
for application-specific authorization.

## Adding services

A service encapsulates a capability behind a Clojure protocol. HTTP routes are
one way to call it; other components can receive the same service instance via
`ig/ref` and invoke its API directly. Domain behavior belongs in the service,
while routes translate requests into API calls and results into Ring responses.
The health service is the complete example of this structure in this repository.

| Namespace | Responsibility |
| --- | --- |
| `silo.service.<name>.api` | Public protocol and functions for constructing domain values; independent of Ring and Jetty. |
| `silo.service.<name>.specs` | Domain data models and validation rules using `clojure.spec`. |
| `silo.service.<name>.infrastructure.<implementation>` | Concrete protocol implementation, such as in-memory state or an external system. |
| `silo.service.<name>.system` | Integrant wiring for construction, dependencies, cleanup, and optional HTTP routes. |

To add a service:

1. Define its contract in `api` and its data shapes in `specs`. Invoke validation
   where needed; declaring a spec alone does not validate API calls or requests.
2. Implement the protocol in `infrastructure`. Expose a constructor that receives
   its dependencies, keeping resource creation out of namespace loading.
3. Add a `system` namespace with a `components` map and an `ig/init-key` method
   for a distinct key such as `:example/service`. Declare required components
   with `ig/ref` and pass their initialized values into the constructor.
4. If the service owns resources, add `ig/halt-key!` to close them. For example,
   `:health/service` calls `api/shutdown!` when halted.
5. If HTTP access is needed, add `:example/routes` with a reference to
   `:example/service`. Its initializer returns a vector of Reitit route vectors,
   with handlers closing over the injected service, as `:health/routes` does.
   Choose serialization, request validation, and response statuses explicitly.
6. Require the new `system` namespace in the application, merge its component
   map, and reference its routes from the application's route assembly.

For example, after implementing `:example/service` and `:example/routes`, replace
the Hello World routes with the service's routes mounted under `/api`:

```clojure
(require '[silo.service.example.system :as examplesys])

(defmethod ig/init-key ::routes
  [_ {:keys [example]}]
  [(into ["/api"] example)])

(defn components [config]
  (merge examplesys/components
         httpsys/components
         {:config config
          ::routes {:example (ig/ref :example/routes)}
          ::router {:routes (ig/ref ::routes)}
          :http.server/handler {:router (ig/ref ::router)}}))
```

This explicitly mounts the service under `/api`. Merely merging a service's
components does not expose its routes. A service without HTTP endpoints needs
only its service component and references from its consumers. Use distinct
component keys: `merge` replaces duplicate entries rather than combining them.

To replace an implementation, change the service's construction wiring while
preserving its protocol and the component key consumed by routes and other
services. Consumers should call the API rather than depend on the concrete
implementation. Test domain behavior through that API and test HTTP translation
separately using the Ring handler.

## Using the health service

Require `silo.service.health.system` and merge its `components` map. Mount
`:health/routes` in the application's router to expose `GET /health` beneath
the chosen prefix, for example `/api/health` when mounted under `/api`.

Components that report health declare a reference to `:health/service` and call
`silo.service.health.api/add-check!` with a check keyword and a status constructed
by `healthy`, `degraded`, or `unhealthy`. The application runs its own checks and
reports their results; the health service does not execute them.

The endpoint requires a trusted client certificate and returns HTTP 200 even
when health is degraded or unhealthy. Consumers must inspect the response body.
