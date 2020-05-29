#!/bin/bash
source $(dirname "$0")/routes_24_hours.sh

INPUT_FILE="https://beam-outputs.s3.amazonaws.com/output/austin/austin-prod-200k-cacc-enabled-flowCap0.20-60hours-better-speeds-final__2020-04-01_21-41-31_spw/ITERS/it.10/10.studyarea.CarRideStats.personal.csv.gz"
# Google shows latitude and them longitude coordinates, but here is in reverse order, longitude and then latitude
BOUNDING_BOX="[(-97.843763,30.481509),(-97.655786,30.180919)]"

generate $INPUT_FILE $DISTANCE $BOUNDING_BOX
