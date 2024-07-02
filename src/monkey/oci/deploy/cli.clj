(ns monkey.oci.deploy.cli
  "Functions to invoke from the cli"
  (:require [aero.core :as ac]
            [monkey.aero] ; For multimethods
            [monkey.oci.deploy.core :as c]
            [taoensso.telemere :as t])
  (:import java.util.Base64))

(defn- load-config [p]
  (t/log! {:data {:path p}} "Loading configuration")
  (ac/read-config p))

(defn show [{:keys [ci-config-file]}]
  (t/log! {:data {:path ci-config-file
                  :config (ac/read-config ci-config-file)}}
          "Container instance configuration"))

(defn redeploy
  "CLI function to redeploy a container instance"
  [{:keys [config config-file lb-filter ci-filter ci-config ci-config-file backends]}]
  (let [conf (or config (load-config config-file))
        res @(c/redeploy conf
                         lb-filter
                         ci-filter
                         (or ci-config (ac/read-config ci-config-file))
                         backends)]
    (t/log! {:data (->> ((juxt :old-ci :new-ci) res)
                        (map :id)
                        (zipmap [:old-ci :new-di]))}
            "Instances redeployed")
    (t/log! {:data (select-keys res [:old-bes :new-bes])}
            "Backends redirected")))
