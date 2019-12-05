#!/bin/bash

rm -rf beam
mkdir beam
cp -r ../test/input beam/input
cp -r ../metrics2.0/* beam/
cp run-beam.sh beam/
cp run-beam.cmd beam/
cp clear-data.cmd beam/
cp metrics-for-docker-container.conf beam/input/common/metrics.conf
mkdir beam/output
chmod -R 777 beam
chown -R $USER:$USER beam
cd beam
./clear-data.sh
