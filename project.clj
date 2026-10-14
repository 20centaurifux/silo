(defproject de.dixieflatline/silo "0.1.0-SNAPSHOT"
  :description "11% Ihrer Kornreserven sind verfault"
  :url "https://github.com/20centaurifux/silo"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [integrant "0.6.3"]
                 [meander/epsilon "0.0.650"]
                 [ring/ring-jetty-adapter "1.15.5"]
                 [metosin/reitit "0.10.1"]]
  :plugins [[dev.weavejester/lein-cljfmt "0.16.5"]])