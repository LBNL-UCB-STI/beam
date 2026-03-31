### Running BEAM manually on Lawrencium cluster

1. Login to the cluster by ssh (for example with web ssh client https://lrc-ondemand.lbl.gov/pun/sys/shell/ssh/default

2. Download a script to run a job and change it to be executable:

```
wget https://raw.githubusercontent.com/LBNL-UCB-STI/beam/develop/lawrencium/run_job.sh && chmod +x run_job.sh
```

3. Edit the script according to needs:

In order to specify what code\data\config should be used, change following variables:

```
export BEAM_BRANCH_NAME="develop"                       ## code branch name and commit
export BEAM_COMMIT_SHA=""                               ##
export BEAM_DATA_BRANCH_NAME="develop"                  ## data repository branch name and commit, if used
export BEAM_DATA_COMMIT_SHA=""                          ##
export BEAM_CONFIG="test/input/beamville/beam.conf"
export PROFILER=""                                      ## empty, 'cpu' or 'cpumem'
```

In order to change which partition, QoS to use and specify amount of memory requested from a node change the following variables in run_job.sh script.
In order to see which partition and queue are available for current user - 
`sacctmgr show association -p user=$USER`.

```
PARTITION="es1"
QOS="es_normal"
MEMORY_LIMIT="480"  ## in GB
```

In order to enable uploading to s3 storage, change the following variables:

```
export S3_REGION="us-east-2"
export S3_PUBLISH="false"           ## true or false
export AWS_SECRET_ACCESS_KEY=""     ## required if S3_PUBLISH is true
export AWS_ACCESS_KEY_ID=""         ## required if S3_PUBLISH is true
```

In order to send notifications to google spreadsheet and slack, change to following variables:

```
export SEND_NOTIFICATION="false"                ## true or false
export SLACK_HOOK_WITH_TOKEN=""                 ## required if SEND_NOTIFICATION is true
export SIMULATIONS_SPREADSHEET_UPDATE_URL=""    ## required if SEND_NOTIFICATION is true
```

4. To start a job (or put it into a queue if there are no free nodes):

```
./run_job.sh <simulation name> <expected execution time>
```

Where expected execution time should be in following format - [days]-[hours]:[minutes]:[seconds]

5. The output will be in the scratch folder, which will be erased in 30 days according to Lawrencium documentation:
`/global/scratch/users/<user name>/<output folder name>`
output folder will have the following name:
`out_beam_<datetime>.<job UID>.<partition name>.<QoS>.<memory limit>`


6. A link to the log of the job will be in the file:

`out.log.<datetime>.<job UID>.<partition name>.<QoS>.<memory limit>.log`

7. The job will have the following name:

`<job UID>.<datetime>`

8. Additional information here - https://github.com/LBNL-UCB-STI/beam/issues/3732

### Running BEAM distributed routing across multiple Lawrencium nodes

Use `lawrencium/run_cluster_job.sh` when you want one master plus remote routing workers.

1. Pick a BEAM config that is small enough for a smoke test first.

2. Choose one image mode:

- default `clone` mode: the container clones/pulls code at job start
- `baked` mode: the image already contains the BEAM code and data you want to run

3. Edit the same code/data/image variables you would edit for `run_job.sh`.

For baked mode, set:

```
export BEAM_IMAGE_MODE="baked"
export PULL_CODE="false"
export PULL_DATA="false"
export DOCKER_IMAGE_NAME="docker://<registry>/<image>:<tag>"
export BEAM_CONFIG="test/input/sf-light/sf-light-5k.conf"
```

The launcher will:

- pull the image as a Singularity/Apptainer `.sif`
- mount a per-role scratch directory at `/root/data`
- leave the baked `/root/sources` tree inside the image untouched
- run the container with `--writable-tmpfs`
- write BEAM outputs under `/root/data/output`

4. Start a multi-node job:

```
./run_cluster_job.sh <simulation name> <expected execution time> <total node count>
```

Example:

```
./run_cluster_job.sh beamville-cluster-smoke 00:30:00 2
```

Contract used by the launcher:

- the first allocated host becomes the master
- every remaining host runs exactly one routing worker JVM
- `beam.cluster.expectedWorkerNodes` is set to the number of non-master hosts
- all nodes use the same fixed Akka port
- the seed address is derived from `<master-host>:<akka-port>`
- the master is launched with `--use-local-worker false`

Logs and staging layout:

- job wrapper log: `out.cluster.<suffix>.log`
- per-node process logs: `/global/scratch/users/<user>/out_beam_<suffix>/logs/`
- per-role mounted workdirs: `/global/scratch/users/<user>/out_beam_<suffix>/master` and one directory per worker host
- in baked mode, BEAM outputs are written under each role directory's `output/`

Smoke-test sequence:

- first run `1 master + 1 worker`
- confirm the worker host joins the cluster in the logs
- confirm routing requests complete remotely before scaling to more workers
