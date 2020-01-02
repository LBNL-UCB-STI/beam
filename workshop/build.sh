#!/bin/bash

rm -rf beam*
mkdir beam
mkdir beam/output
cp -r ../test/input beam/input
cp -r ../metrics2.0/* beam/
cp run-beam.* beam/
cp docker-* beam/
cp clear-data.cmd beam/
cp metrics-for-docker-container.conf beam/input/common/metrics.conf
chmod -R 777 beam
chown -R $USER:$USER beam

cd beam
./clear-data.sh
cd -

if [[ $1 == 'pack' ||  $2 == 'pack' ]]
then
  docker save beammodel/beam:workshop philhawthorne/docker-influxdb-grafana:latest | gzip > beam/workshop-images.tar.gz
  chmod 777 beam/workshop-images.tar.gz
fi


if [[ $1 == 'zip' || $2 == 'zip' ]]
then
  zip -r beam.zip beam/
  chmod 777 beam.zip
fi