#!/bin/bash
# how to run the job (last param is time limit, 1 hour in this case):
# run_job.sh run_environment.sh 1:00:00
#PARTITION="lr_bigmem"
#QOS="lr_normal"

PARTITION="es1"
QOS="es_normal"
MEMORY_LIMIT="500G"  ## i.e. 200G

ACCOUNT="pc_beamcore"

SCRIPT="$1"

EXPECTED_TIME="${2:-0:0:10}"
DATETIME=$(date "+%Y%m%d-%H%M%S")
OUTPUT="out.log.$SCRIPT.$DATETIME.log"
JOBNAME="$SCRIPT.$DATETIME"

# srun - sync run a job
sbatch --partition="$PARTITION" \
    --mem="$MEMORY_LIMIT" \
    --qos="$QOS" \
    --account="$ACCOUNT" \
    --job-name="$JOBNAME" \
    --output="$OUTPUT" \
    --time="$EXPECTED_TIME" \
    "$SCRIPT" "$MEMORY_LIMIT"
