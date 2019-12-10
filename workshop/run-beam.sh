#!/bin/bash

# for Mac, to have current directory as working directory
cd -- "$(dirname "$0")"

config=$1
input="$(pwd)/input"
output="$(pwd)/output"

docker run \
  --name BEAM --rm \
	--mount source=$input,destination=/input,type=bind \
	--mount source=$output,destination=/output,type=bind \
	--link docker-influxdb-grafana:metrics \
	--net beam_default \
  --env JAVA_OPTS='-Xmx8g -Xms4g -Dlogback.configurationFile=logback.xml' \
  beammodel/beam:workshop --config=$config
