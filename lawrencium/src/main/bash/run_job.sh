#!/bin/bash -x
PARTITION="es1"
QOS="es_normal"

MEMORY_LIMIT="480"  ## in G
MEMORY_LIMIT_BEAM="$MEMORY_LIMIT"

ACCOUNT="pc_beamcore"

SCRIPT="$1"
TITLED="$2"
EXPECTED_TIME="${3:-3-00:00:00}"

export AWS_SECRET_ACCESS_KEY=""
export AWS_ACCESS_KEY_ID=""

RANDOM_PART=$(tr -dc A-Z0-9 </dev/urandom | head -c 8)
DATETIME=$(date "+%Y.%m.%d-%H.%M.%S")
SUFFIX="$DATETIME.$RANDOM_PART.$PARTITION.$QOS.$MEMORY_LIMIT"
OUTPUT="out.log.$SCRIPT.$SUFFIX.log"
JOBNAME="$RANDOM_PART.$SCRIPT.$DATETIME"

# srun - sync run a job
sbatch --partition="$PARTITION" \
    --exclusive \
    --mem="${MEMORY_LIMIT}G" \
    --qos="$QOS" \
    --account="$ACCOUNT" \
    --job-name="$JOBNAME" \
    --output="$OUTPUT" \
    --time="$EXPECTED_TIME" \
    "$SCRIPT" "$MEMORY_LIMIT_BEAM" "$TITLED" "$SUFFIX"
