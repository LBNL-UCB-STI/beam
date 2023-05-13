#!/bin/bash

BEAM_CONFIG="test/input/beamville/beam.conf"
BEAM_IMAGE="irishwithaxe/beam-environment:latest"

OUT_PATH="$(pwd)/test_beam_folder"
mkdir -m 777 "$OUT_PATH" 2>/dev/null

runtest1=false
runtest2=true

if [ "$runtest1" ]; then
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

EXISTING_CODE_PATH="$OUT_PATH/beam"

docker run \
  --network host \
  --env MAX_RAM="16" \
  --env BEAM_CONFIG=$BEAM_CONFIG \
  --env BEAM_BRANCH_NAME="develop" \
  --env TITLED="test2" \
  --env PULL_CODE=false \
  --env PULL_DATA=false \
  --env S3_PUBLISH=false \
  --env SEND_NOTIFICATION=false \
  --mount source="$EXISTING_CODE_PATH",destination=/app/sources,type=bind \
  $BEAM_IMAGE
