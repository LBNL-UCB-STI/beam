#!/usr/bin/env bash

pwd > out.log

java -Xmx2g -jar build/libs/beam.jar --config test/input/beamville/beam.conf