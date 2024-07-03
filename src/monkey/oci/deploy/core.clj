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

(defn- error? [{:keys [status]}]
  (and (number? status) (>= status 400)))

(defn- throw-on-error [r]
  (if (error? r)
    (throw (ex-info "Got error response" r))
    r))

(defn- throw-on-any-error
  "Throws when any of the responses contains an error"
  [responses]
  (let [failed (filter error? responses)]
    (if (not-empty failed)
      (throw (ex-info "One or more requests failed" {:failing failed}))
      responses)))

(defn map->filter [m]
  (fn [ci]
    (= m (select-keys ci (keys m)))))

(defn ->filter-fn [x]
  (cond
    (fn? x) x
    (map? x) (map->filter x)
    :else (throw (ex-info "Unable to convert to filter" x))))

(defn list-lbs
  "Lists load balancers in the compartment specified by `conf`"
  [conf]
  (let [ctx (lbc/make-client conf)]
    (md/chain
     (lbc/list-load-balancers ctx (select-keys conf [:compartment-id]))
     throw-on-error
     :body)))

(defn find-lb
  "Retrieves load balancer by filter"
  [conf lb-f]
  (t/log! {:data {:filter lb-f}} "Looking up load balancer")
  (md/chain
   (list-lbs conf)
   (partial filter (->filter-fn lb-f))
   first))

(defn list-cis
  "List container instances in the conf compartment"
  [conf]
  (let [ctx (ci/make-context conf)]
    (ci/list-container-instances ctx (select-keys conf [:compartment-id]))))

(defn find-ci
  "Find container instance by filter.  Filter can be a fn or a map, in which case
   all keyvals of that maps must match."
  [conf f]
  (t/log! {:data {:filter f}} "Looking up container instance")
  (let [ctx (ci/make-context conf)]
    (md/chain
     (ci/list-container-instances ctx (select-keys conf [:compartment-id]))
     throw-on-error
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
     (partial mapcat (comp :body throw-on-error)))))

(defn private-ips [conf ci]
  (md/chain
   (list-vnic-ips conf (:vnics ci))
   (partial map :ip-address)))

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
                          throw-on-error
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
     throw-on-error
     :body
     :id
     (partial wait-until-started ctx))))

(defn delete-ci [conf id]
  (t/log! {:data {:instance-id id}} "Deleting old instance")
  (md/chain
   (ci/delete-container-instance (ci/make-context conf) {:instance-id id})
   throw-on-error))

(defn create-backends
  "Creates backends for the given ip in the load balancer backends."
  [conf ip bs]
  (let [ctx (lbc/make-client conf)
        configs (map (fn [be]
                       {:load-balancer-id (:load-balancer-id be)
                        :backend-set-name (:backend-set be)
                        :backend {:ip-address ip
                                  :port (:port be)}})
                     bs)]
    (md/chain
     (->> configs
          (map (partial lbc/create-backend ctx))
          (apply md/zip))
     (constantly configs))))

(defn drain-backends [conf be]
  (let [ctx (lbc/make-client conf)]
    (md/chain
     (->> be
          (map #(lbc/update-backend ctx
                                    {:load-balancer-id (:load-balancer-id %)
                                     :backend-set-name (:backend-set %)
                                     :backend-name (:backend %)
                                     :backend {:drain true
                                               :backup false
                                               :offline false
                                               :weight 1}}))
          (apply md/zip))
     throw-on-any-error
     (fn [res]
       {:backends be
        :results res}))))

(defn delete-backends [conf be]
  (let [ctx (lbc/make-client conf)]
    (t/log! {:data {:backends be}} "Deleting backends")
    (md/chain
     (->> be
          (map #(lbc/delete-backend ctx
                                    {:load-balancer-id (:load-balancer-id %)
                                     :backend-set-name (:backend-set %)
                                     :backend-name (:backend %)}))
          (apply md/zip))
     throw-on-any-error)))

(defn- make-new-backends [lb dest-backends]
  (map (fn [be]
         (-> be
             (select-keys [:port :backend-set])
             (assoc :load-balancer-id (:id lb))))
       dest-backends))

(def drain-delay-s 10)
(def backend-timeout-s 120)

(defn wait-for-backends
  "Polls until all backends are online, or fails when on timeout"
  [conf bes & [interval]]
  (let [interval (or interval (mt/seconds 5))
        ctx (lbc/make-client conf)]
    (letfn [(->health-args [{:keys [backend] :as be}]
              (-> be
                  (dissoc :backend)
                  (assoc :backend-name (str (:ip-address backend) ":" (:port backend)))))
            (get-health [be]
              (t/log! {:level :debug :backend be} "Checking backend health")
              (md/chain
               (lbc/get-backend-health ctx be)
               ;; Note that health checks can return 404 if the backend is still
               ;; being created.
               :body
               :status
               (fn [s]
                 (t/log! {:data {:status s
                                 :backend be}}
                         "Backend health status")
                 s)))
            (check-all []
              (->> bes
                   (map ->health-args)
                   (map get-health)
                   (apply md/zip)))
            (all-ok? [r]
              (every? (partial = "OK") r))]
      (md/timeout!
       (md/chain
        (md/loop [r (check-all)]
          (md/chain
           r
           (fn [r]
             (if (all-ok? r)
               true
               (md/recur (mt/in interval check-all))))))
        (constantly bes))
       (mt/seconds backend-timeout-s)))))

(defn redeploy [conf lb-f src-inst-f dest-conf dest-backends]
  ;; 1. Create the new instance
  ;; 2. Look up ip address of the instance to replace
  ;; 3. Find backends pointing to the ip address of the old instance
  (t/log! {:instance-name (:display-name dest-conf)} "Redeploying instance")
  (md/let-flow [new (create-and-start-instance conf dest-conf)
                lb (find-lb conf lb-f)
                old (find-ci conf src-inst-f)
                bes (md/chain
                     old
                     (partial private-ips conf)
                     (fn [ips]
                       (find-matching-backends lb ips)))
                new-bes (if (empty? dest-backends)
                          bes
                          (make-new-backends lb dest-backends))
                new-ips (md/chain
                         new
                         :vnics
                         (partial list-vnic-ips conf)
                         (partial map :ip-address)
                         (fn [ips]
                           (t/log! {:data ips} "Ip addresses assigned to new instance")
                           ips))]
    ;; 4. Create new backends for each container in the new instace for the backends
    ;; 5. Wait until all backends are online
    ;; 6. Drain old backends
    ;; 7. Wait and delete old backends
    ;; 8. Delete the old instance
    (md/chain
     new-ips
     first
     (fn [ip]
       (t/log! {:data ip :backends new-bes} "Creating backends for ip")
       (create-backends conf ip new-bes))
     (partial wait-for-backends conf)
     (fn [created-bes]
       (md/chain
        (drain-backends conf bes)
        (constantly created-bes)))
     (fn [created-bes]
       (t/log! {:data {:backends bes
                       :seconds drain-delay-s}}
               "Waiting for connections to be drained")
       (md/chain
        (mt/in (mt/seconds drain-delay-s)
               (fn []
                 (t/log! {:data bes} "Stopping old backends and deleting old container instance")
                 (md/zip
                  (delete-backends conf bes)
                  (delete-ci conf (:id old)))))
        (constantly created-bes)))
     (fn [created-bes]
       (md/chain
        (md/zip old new lb created-bes bes new-ips)
        (partial zipmap [:old-ci :new-ci :lb :new-bes :old-bes :new-ips]))))))

(defn destroy
  "Destroys a container instance and its associated backends."
  [conf id]
  (t/log! {:data {:instance-id id}} "Destroying instance")
  )
