(defproject gossip-glomers "0.0.1"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [cheshire "6.2.0"]
                 [com.hyperfiddle/rcf "20260626-131050"]]
  :plugins [[cider/cider-nrepl "0.42.1"]]
  :profiles {:dev {:source-paths ["dev-src"]}
             ;; lein with-profile -dev,+graal uberjar
             :graal {:dependencies [[com.github.clj-easy/graal-build-time "1.0.5"]]
                     :jvm-opts ["-Dclojure.compiler.direct-linking=true"]
                     :aot :all
                     :uberjar-name "gossip-glomers-graal.jar"}
             ;; lein with-profile -dev,+jvm uberjar
             :jvm {:jvm-opts ["-Dclojure.compiler.direct-linking=true"]
                   :aot :all
                   :uberjar-name "gossip-glomers-jvm.jar"}})

