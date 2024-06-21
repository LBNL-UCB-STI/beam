#!/bin/bash
set -v

output_name="pilates_output_$(date +'%Y%m%d_%H%M%S')"
mkdir -p "$output_name"

output_path=$(realpath "$output_name")

mkdir -p "$output_path/beam/"
mkdir -p "$output_path/activitysim/data/"
mkdir -p "$output_path/urbansim/"
mkdir -p "$output_path/inexus/"
mkdir -p "$output_path/MEP/"

cp -r pilates/beam/beam_output/*/year-* "$output_path/beam/"
cp -r pilates/activitysim/output/final* "$output_path/activitysim/"
cp -r pilates/activitysim/output/year* "$output_path/activitysim/"
cp -r pilates/urbansim/output/year* "$output_path/urbansim/"
cp -r pilates/activitysim/output/pipeline.h5 "$output_path/activitysim/pipeline.h5"
cp -r pilates/activitysim/data/* "$output_path/activitysim/data/"
cp -r pilates/postprocessing/output/ "$output_path/inexus/"
cp -r pilates/postprocessing/MEP/ "$output_path/MEP/"
cp log_pilates_*.out "$output_path/"

set +v

echo "Files are copied: $(du -hs "$output_path")"

echo "Call gzip for all *.csv files inside output folder..."
find "$output_path" -type f -name "*.csv" -exec gzip {} \;
echo "Call gzip for all *.h5 files inside output folder..."
find "$output_path" -type f -name "*.h5" -exec gzip {} \;

echo "PILATES output files are prepared: $(du -hs "$output_path")"
