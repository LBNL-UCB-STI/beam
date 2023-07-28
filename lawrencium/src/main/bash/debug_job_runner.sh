#!/bin/bash

RANDOM_PART="$(tr -dc A-Z0-9 </dev/urandom | head -c 8)"
DATETIME="$(date "+%Y.%m.%d-%H.%M.%S")"
JOB_NAME="$RANDOM_PART.$DATETIME"

PARTITION="es1"
QOS="es_normal"
MEMORY_LIMIT="480"
ACCOUNT="pc_beamcore"
JOB_LOG_FILE_PATH="/global/scratch/users/$USER/test_log_${DATETIME}_${RANDOM_PART}.log"
EXPECTED_EXECUTION_DURATION="0-01:00:00"

set -x

sbatch --partition="$PARTITION" \
    --exclusive \
    --mem="${MEMORY_LIMIT}G" \
    --qos="$QOS" \
    --account="$ACCOUNT" \
    --job-name="$JOB_NAME" \
    --output="$JOB_LOG_FILE_PATH" \
    --time="$EXPECTED_EXECUTION_DURATION" \
    debug_job.sh

set +x
