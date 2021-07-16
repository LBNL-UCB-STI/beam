#!/bin/bash

cd /app/sources
git clone --single-branch --branch $BEAM_BRANCH_NAME https://github.com/LBNL-UCB-STI/beam.git
cd ./beam
git reset --hard $BEAM_COMMIT_SHA
git lfs pull

#we shouldn't use the gradle daemon on NERSC, it seems that it's somehow shared within different nodes
# and all the subsequent runs have output dir somewhere else.
./gradlew --no-daemon clean :run -PappArgs="['--config', '$BEAM_CONFIG']"

if [ "$S3_PUBLISH" == true ]
then
  sleep 10s
  finalPath=""
  for file in output/*; do
     for path2 in $file/*; do
       finalPath="$path2";
     done;
  done;
  echo "Found output dir: $finalPath"
  for file in /app/sources/beam/*.jfr; do
    [ -e "$file" ] || continue
    echo "Zipping $file"
    zip "$file.zip" "$file"
    cp "$file.zip" "$finalPath"
  done;
  cp /app/sources/beam/gc_* "$finalPath"
  aws --region "$S3_REGION" s3 cp "$finalPath" s3://beam-outputs/"$finalPath" --recursive;
  echo "Uploaded to https://s3.$S3_REGION.amazonaws.com/beam-outputs/index.html#$finalPath"
else
  echo "S3 publishing disabled"
fi