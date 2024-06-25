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

(defn find-ci []
  @(d/find-ci conf {:display-name (:display-name test-ci)
                    :lifecycle-state "ACTIVE"}))

(defn private-ips [ci]
  (->> ci
       :vnics
       (d/list-vnic-ips conf)
       deref
       (map :ip-address)))

(defn lbs []
  @(d/list-lbs conf))

(defn delete-ci [id]
  (cc/delete-container-instance (cc/make-context conf) {:instance-id id}))

#_(defn create-backends [lb ci ports]
  (when-let [ip (first (private-ips ci))]
    (->> ports
         (map (fn [p]
                {:port p
                 :load-balancer-id (:id lb)
                 :backend-set nil}))
         (d/create-backends conf ip)
         (deref))))

(defn redeploy []
  (t/log! {:instance-name (:display-name test-ci)} "Redeploying instance")
  (let [new (d/create-and-start-instance conf test-ci)
        lb (first (lbs))
        old (find-ci)
        bes (d/find-matching-backends lb (private-ips old))
        new-ips (md/chain
                 new
                 :vnics
                 (partial d/list-vnic-ips conf)
                 (partial map :ip-address)
                 (fn [ips]
                   (t/log! {:data ips} "Ip addresses assigned to new instance")
                   ips))]
    (t/log! {:data bes} "Found matching backends using existing instance")
    (md/chain
     new-ips
     first
     (fn [ip]
       (t/log! {:data ip} "Creating backends for ip")
       (d/create-backends conf ip bes))
     ;; TODO Wait for new backends to come online
     (fn [_]
       (t/log! {:data bes} "Stopping old backends and deleting old container instance")
       (md/zip
        (d/stop-backends bes)
        (delete-ci (:id old)))))))
