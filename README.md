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

## Usage

TODO

## License

Copyright (c) 2024 by [Monkey Projects BV](https://www.monkey-projects.be)

[MIT License](LICENSE)