#!/bin/bash

INSTANCE_ID=$(curl http://metadata/computeMetadata/v1/instance/id -H "Metadata-Flavor: Google")
INSTANCE_NAME=$(curl http://metadata/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")
INSTANCE_ZONE=$(curl http://metadata/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google")
MACHINE_TYPE=$(curl http://metadata/computeMetadata/v1/instance/machine-type -H "Metadata-Flavor: Google")
HOST_NAME=$(curl http://metadata/computeMetadata/v1/instance/hostname -H "Metadata-Flavor: Google")
RUN_NAME=$(curl http://metadata/computeMetadata/v1/instance/attributes/run_name -H "Metadata-Flavor: Google")
BEAM_CONFIG=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_config -H "Metadata-Flavor: Google")
BEAM_BRANCH=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_branch -H "Metadata-Flavor: Google")
BEAM_COMMIT=$(curl http://metadata/computeMetadata/v1/instance/attributes/beam_commit -H "Metadata-Flavor: Google")
DATA_COMMIT=$(curl http://metadata/computeMetadata/v1/instance/attributes/data_commit -H "Metadata-Flavor: Google")
DATA_BRANCH=$(curl http://metadata/computeMetadata/v1/instance/attributes/data_branch -H "Metadata-Flavor: Google")
STORAGE_PUBLISH=$(curl http://metadata/computeMetadata/v1/instance/attributes/storage_publish -H "Metadata-Flavor: Google")
BATCH_UID=$(curl http://metadata/computeMetadata/v1/instance/attributes/batch_uid -H "Metadata-Flavor: Google")
MAX_RAM=$(curl http://metadata/computeMetadata/v1/instance/attributes/max_ram -H "Metadata-Flavor: Google")
SHUTDOWN_WAIT=$(curl http://metadata/computeMetadata/v1/instance/attributes/shutdown_wait -H "Metadata-Flavor: Google")
SLACK_HOOK_WITH_TOKEN=$(curl http://metadata/computeMetadata/v1/instance/attributes/slack_hook_with_token -H "Metadata-Flavor: Google")
SLACK_TOKEN=$(curl http://metadata/computeMetadata/v1/instance/attributes/slack_token -H "Metadata-Flavor: Google")
SLACK_CHANNEL=$(curl http://metadata/computeMetadata/v1/instance/attributes/slack_channel -H "Metadata-Flavor: Google")
GOOGLE_API_KEY=$(curl http://metadata/computeMetadata/v1/instance/attributes/google_api_key -H "Metadata-Flavor: Google")

function check_simulation_result() {
  log_file="$(find output -maxdepth 2 -mindepth 2 -type d -print -quit)/beamLog.out"
  if [[ ! -f $log_file ]]; then
      echo "Unable to start"
  fi
  last_line=$(tail $log_file -n 1)
  if [[ $last_line == *"Exiting BEAM"* ]]; then
      echo "Run Completed"
  else
      echo "Run Failed"
  fi
}

#get beam sources
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

#get data sources
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

#sending message to the slack channel
hello_msg=$(cat <<EOF
Run Started
Run Name **$RUN_NAME**
Instance name $INSTANCE_NAME
Instance id $INSTANCE_ID
Instance type **$(basename "$MACHINE_TYPE")**
Host name **$HOST_NAME**
Zone $(basename "$INSTANCE_ZONE")
Batch $BATCH_UID
Branch **$BEAM_BRANCH**
Commit $BEAM_COMMIT
EOF
)
echo "$hello_msg"
curl -X POST -H 'Content-type: application/json' --data '{"text":"'"$hello_msg"'"}' "$SLACK_HOOK_WITH_TOKEN"


#building beam
./gradlew assemble
#running beam
export GOOGLE_API_KEY="$GOOGLE_API_KEY"
./gradlew --stacktrace :run -PappArgs="['--config', '$BEAM_CONFIG']" -PmaxRAM="$MAX_RAM"g

# copy to bucket
storage_url=""
if [ "${STORAGE_PUBLISH,,}" != "false" ]; then
  finalPath=""
  for file in "output"/*; do
    for path2 in "$file"/*; do
      finalPath="$path2";
    done;
  done;

  if [ -d "$finalPath" ]; then
    ln -sf ~/cloud-init-output.log "$finalPath"/cloud-init-output.log
    storage_url="https://console.cloud.google.com/storage/browser/beam-core-outputs/$finalPath"
  else
    finalPath="output/cloud-init-logs"
    mkdir -p "$finalPath"
    cloudInitName=$(echo "$(date '+%Y-%m-%d_%H-%M-%S')__${BEAM_CONFIG}__cloud-init-output.log" | tr '/' '_' )
    ln -sf ~/cloud-init-output.log "$finalPath/$cloudInitName"
    storage_url="https://console.cloud.google.com/storage/browser/beam-core-outputs/$finalPath/$cloudInitName"
  fi
  gsutil -m cp -r "$finalPath" "gs://beam-core-outputs/$finalPath"
fi

#Run and publish analysis
echo "-------------------running Health Analysis Script----------------------"
python3 src/main/python/general_analysis/simulation_health_analysis.py
curl -H "Authorization:Bearer $SLACK_TOKEN" -F file=@RunHealthAnalysis.txt -F initial_comment="Beam Health Analysis" -F channels="$SLACK_CHANNEL" "https://slack.com/api/files.upload"

#Slack message
final_status=$(check_simulation_result)
bye_msg=$(cat <<EOF
Run Completed
Run Name** $RUN_NAME**
Instance ID $INSTANCE_ID
Instance type **$(basename "$MACHINE_TYPE")**
Host name **$HOST_NAME**
Zone $INSTANCE_ZONE
Batch $BATCH_UID
Branch **$BEAM_BRANCH**
Commit $BEAM_COMMIT
Status $final_status
Output $storage_url
Shutdown in $SHUTDOWN_WAIT minutes
EOF
)
echo "$bye_msg"
curl -X POST -H 'Content-type: application/json' --data '{"text":"'"$bye_msg"'"}' "$SLACK_HOOK_WITH_TOKEN"

#shutdown instance
sudo shutdown -h +"$SHUTDOWN_WAIT"