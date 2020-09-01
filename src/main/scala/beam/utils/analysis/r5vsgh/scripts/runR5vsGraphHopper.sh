#!/bin/bash
set -x
set -e

PWD=/home/ubuntu/git/beam

cd $PWD
git pull

AUSTIN_ARGS="['--config','test/input/texas/austin-prod-200k.conf','--plan','/home/ubuntu/prj/r5vsgh/plans.csv.gz','--population-sampling-factor','0.05']"
BEAMVILLE_ARGS="['--config','test/input/beamville/beam.conf','--plan','/home/ubuntu/prj/r5vsgh/plans_beamville.csv']"

APP_ARGS=$AUSTIN_ARGS
# APP_ARGS=$BEAMVILLE_ARGS

./gradlew :execute \
  -PmainClass=beam.utils.analysis.r5vsgh.R5vsGraphHopper \
  -PappArgs=$APP_ARGS \
  -PlogbackCfg=logback.xml
