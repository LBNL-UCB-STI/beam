#!/bin/bash

INSTANCE_NAME=$(curl http://metadata/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")
INSTANCE_ZONE=$(curl http://metadata/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google")
BEAM_CONFIG=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_config -H "Metadata-Flavor: Google")
BEAM_BRANCH=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_branch -H "Metadata-Flavor: Google")
BEAM_COMMIT=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_commit -H "Metadata-Flavor: Google")
DATA_COMMIT=$(curl http://metadata/computeMetadata/v1/instance/attributes/data_commit -H "Metadata-Flavor: Google")
DATA_BRANCH=$(curl http://metadata/computeMetadata/v1/instance/attributes/data_branch -H "Metadata-Flavor: Google")
MAX_RAM=$(curl http://metadata/computeMetadata/v1/instance/attributes/max_ram -H "Metadata-Flavor: Google")
SHUTDOWN_WAIT=$(curl http://metadata/computeMetadata/v1/instance/attributes/shutdown_wait -H "Metadata-Flavor: Google")

cd ~/sources/beam
echo "git fetch"
git fetch
echo "GIT_LFS_SKIP_SMUDGE=1 git checkout $BEAM_BRANCH $(date)"
GIT_LFS_SKIP_SMUDGE=1 git checkout $BEAM_BRANCH
echo "git pull"
git pull
echo "git lfs pull"
git lfs pull

echo "git checkout -qf $BEAM_COMMIT"
GIT_LFS_SKIP_SMUDGE=1 git checkout -qf "$BEAM_COMMIT"
RESOLVED_COMMIT=$(git log -1 --pretty=format:%H)
echo "Resolved commit is $RESOLVED_COMMIT"

production_data_submodules=$(git submodule | awk '{ print $2 }')
for i in $production_data_submodules
do
  echo $i
    case $BEAM_CONFIG in
    *$i*)
      echo "Loading remote production data for $i"
      git config submodule.$i.branch "$DATA_BRANCH"
      git submodule update --init --remote "$i"
      cd "$i"
      git checkout "$DATA_COMMIT"
      RESOLVED_DATA_COMMIT=$(git log -1 --pretty=format:%H)
      echo "Resolved data commit is $RESOLVED_DATA_COMMIT"
      cd -
    esac
done

./gradlew assemble
./gradlew --stacktrace :run -PappArgs="['--config', '$BEAM_CONFIG']" -PmaxRAM="$MAX_RAM"g

# copy to bucket
finalPath=""
for file in "output"/*; do
  for path2 in "$file"/*; do
    finalPath="$path2";
  done;
done;
gsutil -m cp -r "$finalPath" gs://beam-core-outputs/"$finalPath"

sudo shutdown -h +"$SHUTDOWN_WAIT"

