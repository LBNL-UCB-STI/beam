#!/bin/bash

# Full list of input parameters required to run the script
input_parameters=(
  BEAM_BRANCH_NAME BEAM_COMMIT_SHA  # code branch and commit
  BEAM_DATA_BRANCH_NAME BEAM_DATA_COMMIT_SHA  # data branch and commit
  BEAM_CONFIG # path to beam config
  RUN_NAME # the name of simulation (will be used in notifications)
  PROFILER  # either empty, 'cpu' or 'cpumem'
  MAX_RAM # max ram for beam

  # BEAM-environment docker image name and docker image tag separately
  # i.e. 'beammodel/beam-environment' and 'latest'
  DOCKER_IMAGE_NAME DOCKER_IMAGE_TAG

  S3_REGION S3_PUBLISH  # if uploading to s3 required - both AWS key parts should be set
  AWS_SECRET_ACCESS_KEY AWS_ACCESS_KEY_ID

  SEND_NOTIFICATION # if true then either or both (slack token and spreadsheet url) should be set
  SLACK_HOOK_WITH_TOKEN  # for sending notifications to slack SLACK_HOOK required
  SIMULATIONS_SPREADSHEET_UPDATE_URL  # for sending updates to the spreadsheet SIMULATIONS_SPREADSHEET_UPDATE_URL required

  # Expected simulation duration, needed for the cluster to understand an order of running jobs if there are not enough nodes.
  # The duration should be a bit longer than simulation should take (approximately).
  # But not longer than maximum possible duration - 3 days for now (3-00:00:00).
  # D-HH:MM:SS, i.e. for 1 day, 2 hours and 30 minutes => 1-02:30:00
  EXPECTED_EXECUTION_DURATION

  ACCOUNT # account used to run jobs on Lawrencium
  PARTITION # which partition and QOS use to run job
  QOS # In order to see which partition and queue are available for current user - sacctmgr show association -p user=$USER.
  MEMORY_LIMIT # memory limit should be in GB
)


# Reading variables case-insensitively from input parameters according to input_parameters list.
# Read variables are exported into environment.
while [ $# -gt 0 ]; do
  for var_name in "${input_parameters[@]}"; do
    var_value=${1#*=}
    # check if variable name in lower case and '=' symbol are in parameter
    # check if variable value is not empty
    if [[ ${1,,} == --"${var_name,,}="* && -n "$var_value" ]] ; then
      export "$var_name"="$var_value"
    fi
  done
  shift
done


# Checking that all required variables were set.
for var_name in "${input_parameters[@]}" ; do
  var_value="${!var_name}"

  if [[ -z "$var_value" ]]; then
    echo "Error! Variable '$var_name' is required!"
    exit 1
  fi

  echo "'$var_name' = '$var_value'"
done


# using the current memory limit as MAX RAM for BEAM simulation
export MAX_RAM="$MEMORY_LIMIT"
# adding current user name as part of simulation title
export NOTIFICATION_TITLED="$USER/$RUN_NAME"


RANDOM_PART="$(tr -dc A-Z0-9 </dev/urandom | head -c 8)"
DATETIME="$(date "+%Y.%m.%d-%H.%M.%S")"
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
SIMULATION_HOST_LOG_FILE="/app/sources/$JOB_LOG_FILE_NAME"


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
sbatch --partition="$PARTITION" \
    --exclusive \
    --mem="${MEMORY_LIMIT}G" \
    --qos="$QOS" \
    --account="$ACCOUNT" \
    --job-name="$JOB_NAME" \
    --output="$JOB_LOG_FILE_PATH" \
    --time="$EXPECTED_EXECUTION_DURATION" \
    slurm_job.sh
set +x