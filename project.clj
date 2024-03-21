(defproject com.rpl/ardoq-rama-poc "1.0.0-SNAPSHOT"
  :dependencies [;; NOTE: Rama has custom version of clojure built in
                 [com.rpl/rama-helpers "0.9.3"]]
  :repositories [["releases" {:id "maven-releases"
                              :url "https://nexus.redplanetlabs.com/repository/maven-public-releases"}]]

  :profiles {:dev {:resource-paths ["test/resources/"]
                   :dependencies [[nrepl "1.1.0"]
                                  ["djblue/portal" "RELEASE" :exclusions [org.clojure/clojure]]]}
             :provided {:dependencies [[com.rpl/rama "0.12.1"]]}}
  )
