#!/bin/bash

if [ -z  "$1" ]; then
    echo "Please provide a valid beam config to start."
else
    echo "Starting BEAM for config: $1."
    gradle run -PappArgs="['--config', '$1']"
    echo 'BEAM Simulation Completed...'
fi


