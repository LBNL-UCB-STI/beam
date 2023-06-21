#!/bin/bash

#
# This script will start beam-environment docker image,
#   it will use the code from mounted folder, the mounted folder should point to BEAM root,
#   it will use the data from mounted data folder if selected config is in there,
#     otherwise it will try to use data from code folder (i.e. from test/input folder)
#   it won't send notifications and won't publish output to s3,
#   the selected config - beamville, will be used from the pulled code.
#
# The output folder will be created before running the docker image.
#

MAX_RAM="16"
BEAM_CONFIG="test/input/beamville/beam.conf"
EXISTING_CODE_PATH="/mnt/data/work/beam/beam_test_folder--2023-06-05--18-44-21"
EXISTING_DATA_PATH="/mnt/data/work/beam/beam-production/test"

docker run \
  --network host \
  --env MAX_RAM=$MAX_RAM \
  --env BEAM_CONFIG=$BEAM_CONFIG \
  --mount source="$EXISTING_CODE_PATH",destination=/app/sources,type=bind \
  --mount source="$EXISTING_DATA_PATH",destination=/app/data,type=bind \
  "beammodel/beam-environment:latest"
