#!/bin/bash

rm -rf beam*
mkdir -p beam/input
cp -r ../test/input/common beam/input/common
cp -r ../test/input/beamville beam/input/beamville
cp -r ../test/input/sf-light* beam/input
cp -r ../metrics2.0/* beam/
cp run-beam.* beam/
cp docker-pull.* beam/
cp clear-data.cmd beam/
cp metrics-for-docker-container.conf beam/input/common/metrics.conf
mkdir beam/output
chmod -R 777 beam
chown -R $USER:$USER beam
cd beam
./clear-data.sh


if [[ $1 == 'pack' ]]
then
  cd ..
  zip -r beam.zip beam/
  chmod 777 beam.zip
fi