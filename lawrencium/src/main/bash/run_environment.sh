#!/bin/bash
export BEAM_BRANCH_NAME="inm/lawrencium-integration"
export BEAM_DATA_BRANCH_NAME="develop"
export BEAM_COMMIT_SHA="8283c90de51a05ebd306b0981ac41b798e5e8ab5"
export BEAM_CONFIG="test/input/beamville/beam.conf"
# export BEAM_CONFIG="production/sfbay/gemini/gemini-scenario-6-Advanced-05p.conf"
# export BEAM_CONFIG="test/input/sf-light/sf-light-25k-modified.conf"

export GOOGLE_API_KEY=""

export AWS_ACCESS_KEY_ID=""
export AWS_SECRET_ACCESS_KEY=""
export S3_PUBLISH="false"
export S3_REGION="us-east-2"


export TITLED="Put test tile here" # the title of a simulation
export SLACK_HOOK_WITH_TOKEN="" # to send notifications to slack

export MAX_RAM="${1,,}"

if [[ -z "$MAX_RAM" ]]; then
    echo "Error: MAXRAM is not set, the script tried to use a value #1 for the variable."
    exit 1
fi

SUFFIX="${2:-$(date "+%Y%m%d-%H%M%S")}"
BEAM_DIR="/global/scratch/users/$USER/out_beam_$SUFFIX"
mkdir "$BEAM_DIR"

MOUNTED_DIR=$(realpath $BEAM_DIR)
IMAGE_NAME="beam-environment_1.92.sif"

singularity run -B "$MOUNTED_DIR:/app/sources" "$IMAGE_NAME"
