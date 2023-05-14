#!/bin/bash

BEAM_CONFIG="test/input/beamville/beam.conf"
BEAM_IMAGE="beam-environment:latest"

pull_from_github_and_run=false
run_with_local_code_data=true

if [ "$pull_from_github_and_run" = true ]; then
  OUT_PATH="$(pwd)/test_beam_folder"
  mkdir -m 777 "$OUT_PATH" 2>/dev/null

  docker run \
    --network host \
    --env MAX_RAM="16" \
    --env BEAM_CONFIG=$BEAM_CONFIG \
    --env BEAM_BRANCH_NAME="develop" \
    --env TITLED="test1" \
    --env PULL_CODE=true \
    --env PULL_DATA=false \
    --env S3_PUBLISH=false \
    --env SEND_NOTIFICATION=false \
    --mount source="$OUT_PATH",destination=/app/sources,type=bind \
    $BEAM_IMAGE
fi

EXISTING_CODE_PATH="/mnt/data/work/beam/beam"
EXISTING_DATA_PATH="/mnt/data/work/beam/beam-production/test"

if [ "$run_with_local_code_data" = true ]; then
  docker run \
    --network host \
    --env MAX_RAM="16" \
    --env BEAM_CONFIG=$BEAM_CONFIG \
    --env TITLED="test2" \
    --env PULL_CODE=false \
    --env PULL_DATA=false \
    --env S3_PUBLISH=false \
    --env SEND_NOTIFICATION=false \
    --mount source="$EXISTING_CODE_PATH",destination=/app/sources,type=bind \
    $BEAM_IMAGE

    # --mount source="$EXISTING_DATA_PATH",destination=/app/data,type=bind \

fi