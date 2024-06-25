(ns monkey.oci.deploy.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.telemere :as t]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [monkey.oci.core :as oc]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.common.utils :as u]
            [monkey.oci.lb.core :as lbc]))

(defn load-config []
  (with-open [r (-> (io/resource "config.edn")
                    (io/reader)
                    (java.io.PushbackReader.))]
    (-> (edn/read r)
        (update :private-key u/load-privkey))))

(defn list-lbs
  "Lists load balancers in the compartment specified by `conf`"
  [conf]
  (let [ctx (lbc/make-client conf)]
    (md/chain
     (lbc/list-load-balancers ctx (select-keys conf [:compartment-id]))
     :body)))

(defn list-cis
  "List container instances in the conf compartment"
  [conf]
  (let [ctx (ci/make-context conf)]
    (ci/list-container-instances ctx (select-keys conf [:compartment-id]))))

(defn map->filter [m]
  (fn [ci]
    (= m (select-keys ci (keys m)))))

(defn ->filter-fn [x]
  (cond
    (fn? x) x
    (map? x) (map->filter x)
    :else (throw (ex-info "Unable to convert to filter" x))))

(defn find-ci
  "Find container instance by filter.  Filter can be a fn or a map, in which case
   all keyvals of that maps must match."
  [conf f]
  (let [ctx (ci/make-context conf)]
    (md/chain
     (ci/list-container-instances ctx (select-keys conf [:compartment-id]))
     :body
     :items
     (partial filter (->filter-fn f))
     (fn [matches]
       (when-not (empty? matches)
         (-> matches
             first
             :id
             (as-> x (ci/get-container-instance ctx {:instance-id x}))
             (md/chain :body)))))))

(defn list-vnic-ips
  "Lists all private ip's that are linked to the list of vnics (as found in results from `find-ci`)"
  [conf vnics]
  (let [ctx (oc/make-client conf)]
    (md/chain
     (->> vnics
          (map #(select-keys % [:vnic-id]))
          (map (partial oc/list-private-ips ctx))
          (apply md/zip))
     (partial mapcat :body))))

(defn find-matching-backends
  "Finds all backends in the load balancer for the given ip address"
  [lb ips]
  (let [s (set ips)]
    (letfn [(find-matches [bs]
              (->> bs
                   :backends
                   (filter matches-backend?)
                   (map (fn [{:keys [name port ip-address]}]
                          {:backend name
                           :backend-set (:name bs)
                           :load-balancer-id (:id lb)
                           :ip-address ip-address
                           :port port}))))
            (matches-backend? [be]
              (s (:ip-address be)))]
      (->> lb
           :backend-sets
           vals
           (mapcat find-matches)))))

(defn- wait-until-started
  "Polls until the container instance has started"
  [ctx cid]
  (let [started? (md/deferred)
        check-started (fn []
                        (t/log! "Checking if instance has started...")
                        @(md/chain
                          (ci/get-container-instance ctx {:instance-id cid})
                          :body
                          (fn [{:keys [lifecycle-state] :as ci}]
                            (t/log! {:data {:lifecycle-state lifecycle-state}} "Lifecycle state")
                            (condp = lifecycle-state
                              "ACTIVE" (md/success! started? ci)
                              "FAILED" (md/error! started? (ex-info "Instance failed to start" ci))
                              (t/log! "Instance is still starting")))))
        stop-polling (mt/every (mt/seconds 10) (mt/seconds 30) check-started)]
    (md/on-realized started?
                    (fn [_] (stop-polling))
                    (fn [_] (stop-polling)))
    started?))

(defn create-and-start-instance
  "Creates given container instance, and waits until it's started."
  [conf ci]
  (let [ctx (ci/make-context conf)]
    (t/log! {:level :info :data {:instance (:display-name ci)}} "Creating container instance")
    (md/chain
     (ci/create-container-instance ctx {:container-instance ci})
     :body
     :id
     (partial wait-until-started ctx))))

(defn create-backends
  "Creates backends for the given ip in the load balancer backends."
  [conf ip bs]
  (let [ctx (lbc/make-client conf)]
    (->> bs
         (map #(lbc/create-backend ctx {:load-balancer-id (:load-balancer-id %)
                                        :backend-set-name (:backend-set %)
                                        :backend {:ip-address ip
                                                  :port (:port %)}}))
         (apply md/zip))))

(defn drain-backends [be]
  (->> be
       (map #(lbc/update-backend {:load-balancer-id (:load-balancer-id %)
                                  :backend-set-name (:backend-set %)
                                  :backend-name (:backend %)
                                  :backend {:drain true}}))
       (apply md/zip)))

(defn delete-backends [be]
  (->> be
       (map #(lbc/delete-backend {:load-balancer-id (:load-balancer-id %)
                                  :backend-set-name (:backend-set %)
                                  :backend-name (:backend %)}))
       (apply md/zip)))

(defn stop-backends [be]
  ;; First drain, then wait a while and then delete the backends.
  (md/chain
   (drain-backends be)
   (fn [_]
     (t/log! {:data {:backends be}} "Waiting for 10 seconds to drain connections")
     (mt/in (mt/seconds 10) #(delete-backends be)))))

(defn redeploy [ctx lb-id src-inst-f dest-conf]
  ;; 1. Create the new instance
  ;; 2. Look up ip address of the instance to replace
  ;; 3. Find backends pointing to the ip address of the old instance
  ;; 4. Create new backends for each container in the new instace for the backends
  ;; 5. Wait until all backends are online
  ;; 6. Drain old backends
  ;; 7. Wait and delete old backends
  ;; 8. Delete the old instance
  )
