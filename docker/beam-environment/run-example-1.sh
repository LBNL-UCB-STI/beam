#!/bin/bash

#
# This script will start beam-environment docker image,
#   it will pull the latest BEAM code (branch 'develop') from github,
#   it won't send notifications and won't publish output to s3,
#   the selected config - beamville, will be used from the pulled code.
#
# The output folder will be created before running the docker image.
#

OUT_PATH="$(pwd)/test_beam_folder"
mkdir -m 777 "$OUT_PATH" 2>/dev/null

MAX_RAM="16"
BEAM_CONFIG="test/input/beamville/beam.conf"
BEAM_BRANCH_NAME="develop"

docker run \
  --network host \
  --env MAX_RAM=$MAX_RAM \
  --env BEAM_CONFIG="$BEAM_CONFIG" \
  --env BEAM_BRANCH_NAME=$BEAM_BRANCH_NAME \
  --env PULL_CODE=true \
  --env PULL_DATA=false \
  --env S3_PUBLISH=false \
  --env SEND_NOTIFICATION=false \
  --mount source="$OUT_PATH",destination=/app/sources,type=bind \
  "beammodel/beam-environment:latest"
