(defproject gossip-glomers "0.0.1"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [cheshire "6.2.0"]
                 [com.hyperfiddle/rcf "20260626-131050"]]
  :plugins [[cider/cider-nrepl "0.42.1"]]
  :profiles {:dev {:source-paths ["dev-src"]}})

