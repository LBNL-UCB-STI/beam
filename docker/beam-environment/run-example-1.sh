#!/bin/bash

#
# This script will start beam-environment docker image,
#   it will pull the latest BEAM code (branch 'develop') from github,
#   it won't send notifications and won't publish output to s3,
#   the selected config - beamville, will be used from the pulled code.
#
# The output folder will be created before running the docker image.
#

OUT_PATH="$(pwd)/../../../beam_test_folder--$(date "+%Y-%m-%d--%H-%M-%S")"
OUT_PATH=$(realpath "$OUT_PATH")
mkdir -m 777 "$OUT_PATH"
echo "Using folder for beam at '$OUT_PATH'"

MAX_RAM="16"
BEAM_CONFIG="test/input/beamville/beam.conf"
BEAM_BRANCH_NAME="develop"

docker run \
  --network host \
  --env MAX_RAM=$MAX_RAM \
  --env BEAM_CONFIG="$BEAM_CONFIG" \
  --env BEAM_BRANCH_NAME=$BEAM_BRANCH_NAME \
  --env PULL_CODE=true \
  --mount source="$OUT_PATH",destination=/app/sources,type=bind \
  "beammodel/beam-environment:latest"
