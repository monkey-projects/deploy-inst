# OCI Deploy Instances

This is a Clojure lib intended to be used to redeploy applications running
in container instances.  Currently there is no way to do this without
downtime: a running instance cannot be modified and there is no support
to shift traffic from one instance to another without some manual
intervention.  This library aims to provide functionality to allow easy
upgrading of container instances.  Using it, you can specify which container
instance needs to be replaced and which backendsets are impacted.  It will
automatically create the new instance, create a backend for it and then
drain the old backend and shut down the old instance.

## Why

I created this lib because I wanted to be able to mimic the deployment
functionality present in Kubernetes, without having to set up a full-blown
cluster.  In OCI it's currently not possible to redeploy a container instance
without downtime.  This library provides basic functionality to remedy that.

## Usage

Although you can include it in other code and invoke the [core namespace](src/monkey/oci/deploy/core.clj)
functions directly, it's more meant to be called using the command line.  For
this, you need [Clojure CLI tools](https://clojure.org/reference/deps_and_cli).

The main function to invoke is `redeploy`, from the [cli namespace](src/monkey/oci/deploy/cli.clj).
You need to pass in the necessary configuration:

 - `config-file`: the file that holds OCI configuration, like credentials and tenancy ocid.
 - `ci-config` or `ci-config-file`: the configuration for the container instance.
 - `ci-filter`: a map that will be used to match any existing instances.
 - `lb-filter`: a map to find the load balancer for traffic routing.
 - `backends`: configuration for the backends.

Include it in your `deps.edn`:
```clojure
{:deps {com.monkeyprojects/deploy-inst {:mvn/version "<version>"}}
 :aliases
 {:deploy
  {:exec-fn monkey.oci.deploy.cli/redeploy}}}
```

You can invoke it using `clj`:
```bash
$ clj -X:deploy
```
Note that you have to pass in any additional configuration on the command line, or
specify `:exec-args` in your `deps.edn` (recommended).

The configuration is loaded using [Aero](https://github.com/juxt/aero?tab=readme-ov-file),
so all functions provided there are available here.  In addition there is a `#base64`
reader literal, that reads a file and converts it to base64.  This way, you can set up
configfile volumes in the instances.

## How it works

The redeploy procedure is executed in several steps:

 1. Create the new container instance, using the specified configuration.
 2. Find the ip address for the existing instance.
 3. Find existing backends for the ip addresses.  We'll need this to remove old routing later.
 4. Create new backends for the new container instance, using the configured ports.
 5. Wait for the backends to come online.
 6. Drain and delete old backends.
 7. Delete the old instance.

After marking the existing backends as draining, we wait for 10 seconds before
we actually delete them.  If no matching container instance is found, nothing
will be deleted.

Note that this lib expects the backendsets and load balancer to already be provisioned.
It will not create any of those, so if they don't exist, it will error out.

## Examples

An example container instance config can look like this:
```clojure
{:display-name "webserver"
 :availability-domain "GARu:EU-FRANKFURT-1-AD-3"
 :compartment-id "ocid1.compartment.oc1..aaaaaaaahxfsiiidq5pdesassc3pnnvozwd3fbi5raj6twjutodnfpinv6ba"
 :shape "CI.Standard.A1.Flex"
 :shape-config {:ocpus 1
                :memory-in-g-bs 1}
 :vnics
 [{:subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaasbiuwybxsnmmg4weerznc32nmapcqtd2hbc24qgdkcusgg6b6a7a"}]

 :containers
 [{:display-name "apache"
   :image-url "docker.io/httpd:2.4"}]
}
```
Note that you need to specify an availability domain, compartment id and subnet id to
provision the instance.

A load balancer or container instance filter is just a map of properties that will
be matched exactly, like this:
```clojure
{:display-name "webserver"
 :lifecycle-state "ACTIVE"
 :freeform-tags {:env "prod"}}
```

Backend configuration is a list of maps that hold port and backend set names:
```clojure
[{:port 80
  :backend-set "http"}]
```
The backend set must already exist in the load balancer.

## TODO

 - Create backendsets if not existing yet.
 - Roll back on failure.
 - Smarter error checking, like retry on a 429 instead of just failing.
 - Add functionality to just delete a container instance and backends without creating a new one.
 - Allow for "dry running"

## License

Copyright (c) 2024 by [Monkey Projects BV](https://www.monkey-projects.be)

[MIT License](LICENSE)