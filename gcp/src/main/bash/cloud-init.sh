#!/bin/bash

INSTANCE_NAME=$(curl http://metadata/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")
INSTANCE_ZONE=$(curl http://metadata/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google")
BEAM_CONFIG=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_config -H "Metadata-Flavor: Google")
MAX_RAM=$(curl http://metadata/computeMetadata/v1/instance/attributes/max_ram -H "Metadata-Flavor: Google")

cd ~/sources/beam
git checkout develop
git pull
git lfs pull
./gradlew assemble
./gradlew --stacktrace :run -PappArgs="['--config', '$BEAM_CONFIG']" -PmaxRAM="$MAX_RAM"g

# copy to bucket
finalPath=""
for file in "output"/*; do
  for path2 in $file/*; do
    finalPath="$path2";
  done;
done;
gsutil -m cp -r "$finalPath" gs://beam-core-outputs/"$finalPath"

sudo shutdown -h +15

