#!/bin/bash

config=$1
input="$(pwd)/input"
output="$(pwd)/output"

docker run \
	--mount source=$input,destination=/input,type=bind \
	--mount source=$output,destination=/output,type=bind \
	--link docker-influxdb-grafana:metrics \
	--net beam_default \
  --env JAVA_OPTS='-Xmx8g -Xms4g -Dlogback.configurationFile=logback.xml' \
  beammodel/beam:workshop --config=$config
