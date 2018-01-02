#!/bin/bash

if [ -z  "$1" ]; then
    echo "Please provide a valid beam config to start."
else
    echo "Starting BEAM for config: $1."
    java -Xmx2g -jar build/libs/beam.jar --config $1 > out.log
    echo 'BEAM Simulation Completed...'
fi


