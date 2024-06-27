(ns monkey.oci.deploy.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.oci.deploy.core :as sut]
            [monkey.oci.lb.core :as lbc]))

(deftest find-matching-backends
  (testing "empty if no backends"
    (is (empty? (sut/find-matching-backends {} ["test-ip"]))))

  (testing "finds backends for ip address"
    (let [lb {:id "test-lb"
              :backend-sets
              {:backend-1
               {:name "backend-1"
                :backends
                [{:name "test-ip:test-port"
                  :ip-address "test-ip"
                  :port "test-port"}
                 {:name "other-ip:test-port"
                  :ip-address "other-ip"
                  :port "test-port"}]}
               :backend-2
               {:name "backend-2"
                :backends
                [{:name "test-ip:test-port-2"
                  :ip-address "test-ip"
                  :port "test-port-2"}
                 {:name "other-ip:test-port-2"
                  :ip-address "other-ip"
                  :port "test-port-2"}]}}}]
      (is (= [{:load-balancer-id "test-lb"
               :backend-set "backend-1"
               :backend "test-ip:test-port"
               :ip-address "test-ip"
               :port "test-port"}
              {:load-balancer-id "test-lb"
               :backend-set "backend-2"
               :backend "test-ip:test-port-2"
               :ip-address "test-ip"
               :port "test-port-2"}]
             (sut/find-matching-backends lb ["test-ip"]))))))

(deftest wait-for-backends
  (testing "periodically checks for backends to come online"
    (let [states (atom ["UNKNOWN" "PENDING" "OK"])
          backends [{:backend {:ip-address "test-ip"
                               :port 100}}]]
      (with-redefs [lbc/get-backend-health (fn [& args]
                                             (if-let [s (first @states)]
                                               (do
                                                 (swap! states rest)
                                                 (md/success-deferred {:body {:status s}}))
                                               (md/error-deferred (ex-info "Unexpected invocation" {:args args}))))]
        (is (true? @(sut/wait-for-backends {} backends 100)))
        (is (empty? @states))))))
