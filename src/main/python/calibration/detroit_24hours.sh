#!/bin/bash
source $(dirname "$0")/routes_24_hours.sh

INPUT_FILE="https://beam-outputs.s3.amazonaws.com/output/detroit/detroit-200k-flowCapacityFactor-0.1__2020-05-19_12-56-04_ayp/ITERS/it.10/10.studyarea.CarRideStats.personal.csv.gz"
# Google shows latitude and them longitude coordinates, but here is in reverse order, longitude and then latitude
BOUNDING_BOX="[(-83.357804,42.470270),(-82.875912,42.248781)]"

generate $INPUT_FILE $DISTANCE $BOUNDING_BOX
