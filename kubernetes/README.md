Overview
--
Kubernetes [https://kubernetes.io/docs/home/][Kubernetes] is container management tool and content of this folder made to spin up 
simulation in k8s cluster.
  
This folder contains 3 directories:
 - Helm organised
    - beam-common
    - beam-cluster
    - beam-standalone
 - default

Each folder got set of yaml files which needs to deploy BEAM Simulation environment and run simulation.

## default
Such folder contains plain Kubernetes deployment file without any templates wrapping. All files are ready 
for by hand deployment via `kubectl create -f %file_name%`.
Part of deployment file are utility, files inside `standalone` and `cluster` folder are for corresponding BEAM simulation deployment.

##### secrets.yaml
Contains encrypted secrets to access to docker hub and s3 bucked contained input configs for simulation.  

##### s3-daemonset.yaml
Daemonset [https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/][Daemonset] is type of deployment
which automatically will be created on each node.

Such custom container is base on FUSE alpine [https://en.wikipedia.org/wiki/Filesystem_in_Userspace][FUSE] which bounds
S3 bucket to workers filesystem, needs to be deployed only once after cluster creation and allows simulation fetch inputs
from S3. 

Dockerfile also placed to the same folder.

#### Standalone

##### standalone/beam-standalone.yaml
Set of deployment configurations which includes Service and Job description.
Service [https://kubernetes.io/docs/concepts/services-networking/service/][Service] is a logical layout which creates DNS record to be matched 
to the hostname and easily access and find it across thw whole k8s deployed applications. 
Also it exposes ports to access from other pods. In current setup is Headless Service implemented because loadbalancing is not needed.

Job [https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/][Job] kind of controllers which track pods until 
successful completion. Normal pod will be recreated, but Job allows single execution. Only in case of failure it would be recreated. 

#### Cluster
Cluster folder contains 2 configurations, for master and workers

##### cluster/beam-master-cluster-service.yaml
Almost the same configuration, contained Service and Job as `standalone/beam-standalone.yaml`, but prepared to work in 
akka cluster mode via container runtime arguments.

##### cluster/beam-worker-cluster-service.yaml
Deployment configuration which describes workers of simulation. It contains Service and StatefulSet components.

Headless Service is needed for network identity of instances like above and required by StatefulSet.

StatefulSet is kind of controllers which allows ordered and persisted deployment. It creates identical pods, 
based on container configuration.

#### Deployment

The deployment flow is following:
   - Push `secrets.yaml` and `s3-daemonset.yaml`, daemonset copies simulation inputs to the `/dev/data-s3fs`
   - Push `standalone` or `cluster` configurations
To change simulation type, update container args `/opt/config/sf-light/sf-light-25k.conf`.
Inputs placed to the `/opt/output` folder on each node.

Helm
--
Helm [https://helm.sh/docs/][Helm] is template engine which allows flexible and 
easier configuration, packaging and release tracking of k8s deployment. Every set of configurations calls `Chart`

It contains predefined file and folder structure with reserved filenames:
 - `Chart.yaml` manifest for chart contained name, version, description
 - `values.yaml` substitution for value inside `templates`
 - `requirements.yaml` define dependencies
 - `templates/_helpers.tpl` helper function to provide substitution inside templates
 - `templates` folder which contains configuration templates

Charts supports hierarchy and value overriding.

#### beam-common
Contains utility templates to organise namespace, resource quotas, secrets. 

#### beam-standalone
Depends on `beam-common` and contains standalone simulation deployment.

#### beam-cluster
Depends on `beam-common` and contains cluster simulation deployment.

### Build release
Project build file includes gradle helm plugin and to package release just run a gradle command:
- `gradle helmPackageCommonChart` - for beam-common 
- `gradle helmPackageStandaloneChart` - for beam-standalone 
- `gradle helmPackageClusterChart` - for beam-cluster
 
### Deploy
Before deployment check `kubectl config current-context` that k8s client is map to the proper context.

Gradle deployment command already contains build phase and doesnt need any prerequisites except kubectl configuration.

- `gradle deployStandaloneChart` will package and deploy standalone version
- `gradle deployClusterChart`  will package and deploy cluster simulation

To check currently deployed releases run `helm list`

To remove all standalone release from k8s `gradle removeClusterChart` and `gradle removeClusterChart` for cluster version.

Useful commands:
 - `kubectl get pods --all-namespaces` will show all pods
 - `kubectl -n %namespace% logs %pod_name%` will show logs from corresponding pod
 - `kubectl -n %namespace% describe pods|daemonsets.apps|jobs.batch|nodes|services|statefulsets.apps %resource_name%` get detailed info

[Daemonset]: https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/

[FUSE]: https://en.wikipedia.org/wiki/Filesystem_in_Userspace

[Service]: https://kubernetes.io/docs/concepts/services-networking/service/

[Job]: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/

[Helm]: https://helm.sh/docs/

[Kubernetes]: https://kubernetes.io/docs/home/