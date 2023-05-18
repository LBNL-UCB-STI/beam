#!/bin/bash
export BEAM_BRANCH_NAME="inm/lawrencium-integration"
export BEAM_COMMIT_SHA="8283c90de51a05ebd306b0981ac41b798e5e8ab5"
export BEAM_DATA_BRANCH_NAME="develop"
export BEAM_DATA_COMMIT_SHA=""
export BEAM_CONFIG="test/input/beamville/beam.conf"
# export BEAM_CONFIG="production/sfbay/gemini/gemini-scenario-7-Advanced-05p.conf"
# export BEAM_CONFIG="test/input/sf-light/sf-light-25k-modified.conf"

export GOOGLE_API_KEY=""
export AWS_SECRET_ACCESS_KEY=""
export AWS_ACCESS_KEY_ID=""

export S3_PUBLISH="true"
export S3_REGION="us-east-2"
export SLACK_HOOK_WITH_TOKEN="https://hooks.slack.com/services/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"
export SHUTDOWN_WAIT="0"

# getting some of values for running image from environment
export INSTANCE_ID=$SLURMD_NODENAME
export INSTANCE_TYPE="Lawrencium $SLURM_JOB_PARTITION"
export HOST_NAME=$HOSTNAME

export MAX_RAM="${1}"
export TITLED="${2}"

export WEB_BROWSER="TODO"
export PROFILER="TODO"

export INSTANCE_REGION=""

if [[ -z "$MAX_RAM" ]]; then
    echo "Error: MAX_RAM is not set, the script tried to use a parameter #1 for the variable."
    exit 1
fi

if [[ -z "$TITLED" ]]; then
    echo "Error: TITLED is not set, the script tried to use a parameter #2 for the variable."
    exit 1
else
    TITLED="$USER/$TITLED"
fi

SUFFIX="${3:-$(date "+%Y%m%d-%H%M%S")}"
BEAM_DIR="/global/scratch/users/$USER/out_beam_$SUFFIX"
mkdir "$BEAM_DIR"

MOUNTED_DIR=$(realpath "$BEAM_DIR")
IMAGE_NAME="beam-environment_2.0.sif"
singularity run -B "$MOUNTED_DIR:/app/sources" "$IMAGE_NAME"
