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