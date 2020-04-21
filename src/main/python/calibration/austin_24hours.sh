#!/bin/bash

INPUT_FILE="https://beam-outputs.s3.amazonaws.com/output/austin/austin-prod-200k-cacc-enabled-flowCap0.20-60hours-better-speeds-final__2020-04-01_21-41-31_spw/ITERS/it.10/10.studyarea.CarRideStats.personal.csv.gz"
DISTANCE="[3000.0,15000.0]"
BOUNDING_BOX="[(-97.843763,30.481509),(-97.655786,30.180919)]"
SAMPLE_SIZE=1000
MIN_HOUR=0
MAX_HOUR=24

sample () {
  hour=$1
  minSeconds=$((hour*3600))
  maxSeconds=$(((hour+1)*3600-1))
  departueTime=$(printf '[%d, %d]' $minSeconds $maxSeconds)
  python3 generate_rides_with_google_maps.py --inputFile=$INPUT_FILE \
    --travelDistanceIntervalInMeters="${DISTANCE}" --areaBoundBox="${BOUNDING_BOX}" --sampleSize="${SAMPLE_SIZE}" --sampleSeed=12345 --departureTimeIntervalInSeconds="${departueTime}"
}

for i in $(seq $MIN_HOUR $MAX_HOUR)
do
   sample i
done

