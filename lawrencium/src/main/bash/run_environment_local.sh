#!/bin/bash

BEAM_CONFIG="test/input/beamville/beam.conf"
EXISTING_CODE_PATH="/mnt/data/work/beam/beam"
BEAM_IMAGE="irishwithaxe/beam-environment:latest"

docker run \
  --network host \
  --env MAX_RAM="16" \
  --env BEAM_CONFIG=$BEAM_CONFIG \
  --env PULL_CODE=false \
  --env PULL_DATA=false \
  --env S3_PUBLISH=false \
  --env SEND_NOTIFICATION=false \
  --mount source="$EXISTING_CODE_PATH",destination=/app/sources,type=bind \
  $BEAM_IMAGE
