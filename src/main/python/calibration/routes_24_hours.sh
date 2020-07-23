#!/bin/bash

DISTANCE="[3000.0,15000.0]"
SAMPLE_SIZE=1000
MIN_HOUR=0
MAX_HOUR=24

function sample () {
  hour=$1
  minSeconds=$((hour*3600))
  maxSeconds=$(((hour+1)*3600-1))
  departueTime=$(printf '[%d, %d]' $minSeconds $maxSeconds)
  python3 generate_rides_with_google_maps.py --inputFile=$INPUT_FILE \
    --travelDistanceIntervalInMeters="${DISTANCE}" --areaBoundBox="${BOUNDING_BOX}" --sampleSize="${SAMPLE_SIZE}" --sampleSeed=12345 --departureTimeIntervalInSeconds="${departueTime}"
}

function generate() {
  INPUT_FILE=$1
  DISTANCE=$2
  BOUNDING_BOX=$3
  for i in $(seq $MIN_HOUR $MAX_HOUR)
  do
     sample i
  done
}

