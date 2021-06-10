#!/bin/bash

config=$1
beam_image="beammodel/beam:0.8.6"
input_folder_name="test"
output_folder_name="beam_output"
mkdir -m 777 $output_folder_name 2>/dev/null

max_ram='10g'
java_opts="-Xmx$max_ram -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap"

docker run \
  --network host \
  --env JAVA_OPTS="$java_opts" \
  --mount source="$(pwd)/$input_folder_name",destination=/app/$input_folder_name,type=bind \
  --mount source="$(pwd)/$output_folder_name",destination=/app/output,type=bind \
  $beam_image --config=$config
