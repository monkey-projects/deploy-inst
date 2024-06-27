(ns user
  (:require [manifold.deferred :as md]
            [monkey.oci.deploy.core :as d]
            [monkey.oci.container-instance.core :as cc]
            [taoensso.telemere :as t]))

(def conf (d/load-config))

(def test-ci
  {:display-name "test-webserver"
   :availability-domain "GARu:EU-FRANKFURT-1-AD-3"
   :compartment-id (:compartment-id conf)
   :shape "CI.Standard.A1.Flex"
   :shape-config {:ocpus 1
                  :memory-in-g-bs 1}
   :vnics
   [{:subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaasbiuwybxsnmmg4weerznc32nmapcqtd2hbc24qgdkcusgg6b6a7a"}]
   :containers
   [{:display-name "webserver"
     :image-url "docker.io/httpd:2.4"}]})

(defn redeploy []
  (d/redeploy conf
              {:freeform-tags {:env "test"}}
              {:display-name "test-webserver"
               :lifecycle-state "ACTIVE"}
              test-ci
              [{:port 80
                :backend-set "test-web"}]))
