#!/usr/bin/env bash

cd ~/beam

java -Xmx2g -jar build/libs/beam.jar --config test/input/beamville/beam.conf > ~/out.log
