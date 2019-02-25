#!/bin/bash
# Script to run zipAndShipToS3.sh

cd git/beam/production/application-sfbay/calibration/experiments/*/
exp_id="${PWD##*/}"
#echo $exp_id
cd suggestions
for d in */ ; do
#echo "${d::-1}"
sudo ~/git/beam/src/main/bash/zipAndShipToS3.sh "${d::-1}" sigOptExperiments/"$exp_id"
echo "https://s3.us-east-2.amazonaws.com/beam-outputs/sigOptExperiments/$exp_id/${d::-1}.tar.gz"
done
