#!/bin/bash

config=$1
image="beammodel/beam:0.8.6.2"
input_folder_name="test"
output_folder_name="beam_output"
mkdir -m 777 $output_folder_name 2>/dev/null

docker run \
	--mount source="$(pwd)/$input_folder_name",destination=/app/$input_folder_name,type=bind \
	--mount source="$(pwd)/$output_folder_name",destination=/app/output,type=bind \
	$image --config=$config
