#!/bin/bash

# This is the script to run BEAM simulation on Lawrencium cluster.
# By-default it expects 2 input arguments: <run name> <expected execution time>,
# though both might be manually filled in the script body.

CODE_PHRASE="Execute the body of the job."

# Doing shell magic - the script will send itself as a job to the cluster,
# and first argument will tell what to do - start a job or execute the job body.
# This way we could avoid using two shell scripts - one to start a job and another as job body.
# if the first argument is not what we are looking for - then this shell script is used to start a job
if [[ "$1" != "$CODE_PHRASE" ]]; then
  echo "Starting the job .."

  # what code, data and config to use for simulation
  export BEAM_BRANCH_NAME="develop"
  export BEAM_COMMIT_SHA=""
  export BEAM_DATA_BRANCH_NAME="develop"
  export BEAM_DATA_COMMIT_SHA=""
  export BEAM_CONFIG="test/input/beamville/beam.conf"
  export PROFILER=""    # either empty, 'cpu' or 'cpumem'

  export PULL_CODE="true"
  export PULL_DATA="true"

  # In order to see which partition and queue are available for current user - sacctmgr show association -p user=$USER.
  PARTITION="es1"
  QOS="es_normal"
  MEMORY_LIMIT="480"  ## in GB

  # if uploading to s3 required - both AWS key parts should be set
  export S3_REGION="us-east-2"
  export S3_PUBLISH="false"
  export AWS_SECRET_ACCESS_KEY=""
  export AWS_ACCESS_KEY_ID=""

  # for sending notifications to slack SLACK_HOOK required
  # for sending updates to the spreadsheet SIMULATIONS_SPREADSHEET_UPDATE_URL required
  export SEND_NOTIFICATION="false"
  export SLACK_HOOK_WITH_TOKEN=""
  export SIMULATIONS_SPREADSHEET_UPDATE_URL=""

  ACCOUNT="pc_beamcore"

  # INPUT Argument #1 - run name
  RUN_NAME="$1"
  # INPUT Argument #2 - expected simulation duration, needed for the cluster 
  #     to understand an order of running jobs if there are not enough nodes.
  # The duration should be a bit longer than simulation should take (approximately).
  # But not longer than maximum possible duration - 3 days for now (3-00:00:00).
  EXPECTED_EXECUTION_DURATION="$2" # D-HH:MM:SS, i.e. for 1 day, 2 hours and 30 minutes => 1-02:30:00

  # required for doing speed comparison (BEAM simulation vs Google observations)
  export GOOGLE_API_KEY=""

  if [[ -z "$MEMORY_LIMIT" ]]; then
    echo "Error: MEMORY_LIMIT is not set."
    exit 1
  else
    # using the current memory limit as MAX RAM for BEAM simulation
    export MAX_RAM="$MEMORY_LIMIT"
  fi

  if [[ -z "$RUN_NAME" ]]; then
    echo "Error: RUN_NAME is not set."
    exit 1
  else
    # adding current user name as part of simulation title
    export NOTIFICATION_TITLED="$USER/$RUN_NAME"
  fi

  RANDOM_PART=$(tr -dc A-Z0-9 </dev/urandom | head -c 8)
  DATETIME=$(date "+%Y.%m.%d-%H.%M.%S")
  NAME_SUFFIX="$DATETIME.$RANDOM_PART.$PARTITION.$QOS.$MEMORY_LIMIT"

  # The TEMP directory for this simulation, there will be stored code and data.
  # The simulation output will be there as well.
  # /global/scratch will be cleaned after some inactive time, so, it is no advised to be used long-term.
  BEAM_BASE_DIR="/global/scratch/users/$USER/out_beam_$NAME_SUFFIX"
  export BEAM_DIR="$BEAM_BASE_DIR/beam"
  mkdir -p "$BEAM_DIR"

  # Log file will be inside of image mounted folder initially
  # In current folder will be only a link, which will be deleted at the end of this script.
  JOB_LOG_FILE_NAME="cluster-log-file.log"
  JOB_LOG_FILE_PATH="$BEAM_BASE_DIR/$JOB_LOG_FILE_NAME"
  LINK_TO_JOB_LOG_FILE="$(pwd)/out.log.$NAME_SUFFIX.log"
  SIMULATION_HOST_LOG_FILE="/root/sources/$JOB_LOG_FILE_NAME"

  # creating an empty log file and a link to it
  touch "$JOB_LOG_FILE_PATH"
  ln -s "$JOB_LOG_FILE_PATH" "$LINK_TO_JOB_LOG_FILE"

  export JOB_LOG_FILE_PATH
  export LINK_TO_JOB_LOG_FILE
  export SIMULATION_HOST_LOG_FILE

  # Job name starts from random part which is generated GUID for this job
  # It is made for convenience because this way it is easier to match beam output to the job info from the cluster
  JOB_NAME="$RANDOM_PART.$DATETIME"

  # Two SLURM commands to run a job:
  #   srun    - run a job directly without returning control
  #   sbatch  - queue a job and return control to user

  set -x
  # The last row in this command is the script name itself with a special argument.
  # See comments to if-else blocks.
  sbatch --partition="$PARTITION" \
      --exclusive \
      --mem="${MEMORY_LIMIT}G" \
      --qos="$QOS" \
      --account="$ACCOUNT" \
      --job-name="$JOB_NAME" \
      --output="$JOB_LOG_FILE_PATH" \
      --time="$EXPECTED_EXECUTION_DURATION" \
      "$0" "$CODE_PHRASE"
  set +x

else # this shell script is used as a BODY for the job which will be executed on a cluster node
  echo "Executing the job .."

  export NOTIFICATION_INSTANCE_ID=$SLURMD_NODENAME
  export NOTIFICATION_INSTANCE_TYPE="Lawrencium $SLURM_JOB_PARTITION"
  export NOTIFICATION_HOST_NAME=$HOSTNAME

  export NOTIFICATION_WEB_BROWSER="TODO"

  # instance (node) has no region when we using Lawrencium
  export NOTIFICATION_INSTANCE_REGION=""
  # there is no shutdown wait when we using Lawrencium
  export NOTIFICATION_SHUTDOWN_WAIT=""

  IMAGE_NAME="beam-environment"
  IMAGE_TAG="latest"
  DOCKER_IMAGE_NAME="docker://beammodel/${IMAGE_NAME}:${IMAGE_TAG}"
  SINGULARITY_IMAGE_NAME="${IMAGE_NAME}_${IMAGE_TAG}.sif"

  # to use https for pulling data repository despite a url configured for it
  export ENFORCE_HTTPS_FOR_DATA_REPOSITORY="true"

  echo "Pulling docker image '$DOCKER_IMAGE_NAME' ..."
  set -x
  singularity pull --force "$DOCKER_IMAGE_NAME"
  set +x

  echo "Running singularity image '$SINGULARITY_IMAGE_NAME' ..."
  singularity run -B "$BEAM_DIR:/root/sources" "$SINGULARITY_IMAGE_NAME"

  echo "Removing a link to the job's log file."
  echo "The original job log file is in '$JOB_LOG_FILE_PATH'"
  rm "$LINK_TO_JOB_LOG_FILE"

  echo "Done."
fi