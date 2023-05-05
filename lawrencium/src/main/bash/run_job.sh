#!/bin/bash
#PARTITION="lr_bigmem"
#QOS="lr_normal"
#MEMORY_LIMIT="400G"  ## i.e. 200G

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
    "$SCRIPT" "$DATETIME"
