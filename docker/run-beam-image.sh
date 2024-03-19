#!/bin/bash

config="test/input/beamville/beam.conf"
beam_image="beammodel/beam:0.9.0.0"
input_folder_name="test"
output_folder_name="output"
mkdir -m 777 $output_folder_name 2>/dev/null

max_ram='5g'
java_opts="-Xmx$max_ram"

# one can add --user $UID param to have running the container with the current user rights
docker run --rm \
  --network host \
  --env JAVA_OPTS="$java_opts" \
  --mount source="$(pwd)/$input_folder_name",destination=/app/$input_folder_name,type=bind \
  --mount source="$(pwd)/$output_folder_name",destination=/app/output,type=bind \
  $beam_image --config=$config
