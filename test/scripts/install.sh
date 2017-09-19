#!/usr/bin/env bash

cd ~/beam

echo 'Starting BEAM...'
java -Xmx2g -jar build/libs/beam.jar --config test/input/beamville/beam.conf > ~/beam/logs/out.log &
echo 'BEAM Simulation Completed...'

sleep 60
