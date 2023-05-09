#!/bin/bash

INSTANCE_ID=$(curl http://metadata/computeMetadata/v1/instance/id -H "Metadata-Flavor: Google")
INSTANCE_NAME=$(curl http://metadata/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")
INSTANCE_ZONE=$(basename "$(curl http://metadata/computeMetadata/v1/instance/zone -H 'Metadata-Flavor: Google')")
MACHINE_TYPE=$(basename "$(curl http://metadata/computeMetadata/v1/instance/machine-type -H 'Metadata-Flavor: Google')")
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
RUN_JUPYTER=$(curl http://metadata/computeMetadata/v1/instance/attributes/run_jupyter -H "Metadata-Flavor: Google")
RUN_BEAM=$(curl http://metadata/computeMetadata/v1/instance/attributes/run_beam -H "Metadata-Flavor: Google")
JUPYTER_TOKEN=$(curl http://metadata/computeMetadata/v1/instance/attributes/jupyter_token -H "Metadata-Flavor: Google")
JUPYTER_IMAGE=$(curl http://metadata/computeMetadata/v1/instance/attributes/jupyter_image -H "Metadata-Flavor: Google")


function check_simulation_result() {
  log_file="$(find output -maxdepth 2 -mindepth 2 -type d -print -quit)/beamLog.out"
  if [[ ! -f $log_file ]]; then
      echo "Unable to start"
      return
  fi
  last_line=$(tail $log_file -n 1)
  if [[ $last_line == *"Exiting BEAM"* ]]; then
      echo "Run Completed"
  else
      echo "Run Failed"
  fi
}

nohup python3 -m http.server 8000 -d /home &> /dev/null &

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
Instance type **$MACHINE_TYPE**
Host name **$HOST_NAME**
Zone $INSTANCE_ZONE
Batch $BATCH_UID
Branch **$BEAM_BRANCH**
Commit $BEAM_COMMIT
EOF
)
echo "$hello_msg"
curl -X POST -H 'Content-type: application/json' --data '{"text":"'"$hello_msg"'"}' "$SLACK_HOOK_WITH_TOKEN"

# spreadsheet data
start_json=$(cat <<EOF
{
  "command":"add",
  "type":"beam",
  "run":{
    "status":"Run Started",
    "name":"$RUN_NAME",
    "instance_id":"$INSTANCE_NAME",
    "instance_type":"$MACHINE_TYPE",
    "host_name":"$HOST_NAME",
    "browser":"http://$HOST_NAME:8000",
    "branch":"$BEAM_BRANCH",
    "commit":"$RESOLVED_COMMIT",
    "data_branch":"$DATA_BRANCH",
    "data_commit":"$RESOLVED_DATA_COMMIT",
    "region":"$INSTANCE_ZONE",
    "batch":"$BATCH_UID",
    "s3_link":"",
    "max_ram":"$MAX_RAM",
    "profiler_type":"",
    "config_file":"$BEAM_CONFIG",
    "sigopt_client_id":"",
    "sigopt_dev_id":""
  }
}
EOF
)
echo "$start_json"
curl -X POST "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet" -H "Content-Type:application/json" --data "$start_json"

set -x

./gradlew assemble

if [ "${RUN_JUPYTER,,}" = "true" ]; then
  echo "Running Jupyter"
  export GOOGLE_API_KEY="$GOOGLE_API_KEY"

  if [ -n "$JUPYTER_TOKEN" ] && [ -n "$JUPYTER_IMAGE" ]
  then ./gradlew jupyterStart -Puser=root -PjupyterToken="$JUPYTER_TOKEN" -PjupyterImage="$JUPYTER_IMAGE"
  elif [ -n "$JUPYTER_TOKEN" ]
  then ./gradlew jupyterStart -Puser=root -PjupyterToken="$JUPYTER_TOKEN"
  elif [ -n "$JUPYTER_IMAGE" ]
  then ./gradlew jupyterStart -Puser=root -PjupyterImage="$JUPYTER_IMAGE"
  else ./gradlew jupyterStart -Puser=root
  fi

else
  echo "NOT going to start jupyter. [RUN_JUPYTER ('${RUN_JUPYTER,,}') not equal to 'true']"
fi


if [ "${RUN_BEAM,,}" = "true" ]; then
  echo "Running BEAM"
  export GOOGLE_API_KEY="$GOOGLE_API_KEY"
  # somehow there are not enough permissions for gradle lock file if jupyter was run before
  sudo ./gradlew --stacktrace :run -PappArgs="['--config', '$BEAM_CONFIG']" -PmaxRAM="$MAX_RAM"g
else
  echo "NOT going to start BEAM. [RUN_BEAM ('${RUN_BEAM,,}') not equal to 'true']"
fi


set +x


# copy to bucket
storage_url=""
finalPath=""
cloud_init_output_path=""
for file in "output"/*; do
  for path2 in "$file"/*; do
    finalPath="$path2";
  done;
done;


if [ "${STORAGE_PUBLISH,,}" != "false" ]; then

  if [ -d "$finalPath" ]; then #beam started;
    # upload everything to the storage
    cloud_init_output_path="$finalPath/cloud-init-output.log"
    storage_url="https://console.cloud.google.com/storage/browser/beam-core-outputs/$finalPath"
    gsutil -m cp -r "$finalPath" "gs://beam-core-outputs/$finalPath"
  else # beam not started
    # upload the cloud-init-output.log to the storage (at the end of script)
    cloud_init_name=$(echo "$(date '+%Y-%m-%d_%H-%M-%S')__${BEAM_CONFIG}__cloud-init-output.log" | tr '/' '_' )
    cloud_init_output_path="output/cloud-init-logs/$cloud_init_name"
    storage_url="https://console.cloud.google.com/storage/browser/_details/beam-core-outputs/$cloud_init_output_path"
  fi
fi


#Run and publish analysis
health_metrics=""
if [ -d "$finalPath" ]; then
    echo "-------------------running Health Analysis Script----------------------"
    simulation_health_analysis_output_file="simulation_health_analysis_result.txt"
    # somehow there are not enough permissions to create this file if jupyter was run before
    sudo touch $simulation_health_analysis_output_file
    sudo chmod 777 $simulation_health_analysis_output_file
    python3 src/main/python/general_analysis/simulation_health_analysis.py $simulation_health_analysis_output_file
    # load analysis results into variables
    while IFS="," read -r metric count
    do
      export "$metric"="$count"
      health_metrics="$health_metrics, $metric:$count"
    done < $simulation_health_analysis_output_file
    health_metrics="{$(echo "$health_metrics" | cut -c3-)}"
    echo "$health_metrics"
    if [ "${STORAGE_PUBLISH,,}" != "false" ]; then
      gsutil cp "$simulation_health_analysis_output_file" "gs://beam-core-outputs/$finalPath/$simulation_health_analysis_output_file"
    fi
    curl -H "Authorization:Bearer $SLACK_TOKEN" -F file=@$simulation_health_analysis_output_file -F initial_comment="Beam Health Analysis" -F channels="$SLACK_CHANNEL" "https://slack.com/api/files.upload"
fi


if [ "${RUN_BEAM,,}" != "true" ] && [ "${RUN_JUPYTER,,}" = "true" ]; then
  final_status="Jupyter Instance"
else
  final_status=$(check_simulation_result)
fi


#Slack message
bye_msg=$(cat <<EOF
Run Completed
Run Name **$RUN_NAME**
Instance ID $INSTANCE_ID
Instance type **$MACHINE_TYPE**
Host name **$HOST_NAME**
Zone $INSTANCE_ZONE
Batch $BATCH_UID
Branch **$BEAM_BRANCH**
Commit $BEAM_COMMIT
Status $final_status
Health Metrics $health_metrics
Output $storage_url
Shutdown in $SHUTDOWN_WAIT minutes
EOF
)
echo "$bye_msg"
curl -X POST -H 'Content-type: application/json' --data '{"text":"'"$bye_msg"'"}' "$SLACK_HOOK_WITH_TOKEN"


# spreadsheet data
stop_json=$(cat <<EOF
{
  "command":"add",
  "type":"beam",
  "run":{
    "status":"$final_status",
    "name":"$RUN_NAME",
    "instance_id":"$INSTANCE_NAME",
    "instance_type":"$MACHINE_TYPE",
    "host_name":"$HOST_NAME",
    "browser":"http://$HOST_NAME:8000",
    "branch":"$BEAM_BRANCH",
    "commit":"$RESOLVED_COMMIT",
    "data_branch":"$DATA_BRANCH",
    "data_commit":"$RESOLVED_DATA_COMMIT",
    "region":"$INSTANCE_ZONE",
    "batch":"$BATCH_UID",
    "s3_link":"$storage_url",
    "max_ram":"$MAX_RAM",
    "profiler_type":"",
    "config_file":"$BEAM_CONFIG",
    "stacktrace":"$stacktrace",
    "died_actors":"$actorDied",
    "error":"$error",
    "warning":"$warn",
    "sigopt_client_id":"",
    "sigopt_dev_id":""
  }
}
EOF
)
echo "$stop_json"
curl -X POST "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet" -H "Content-Type:application/json" --data "$stop_json"


# uploading cloud-init-output.log again to have the latest output
if [ "${STORAGE_PUBLISH,,}" != "false" ]; then
  gsutil cp ~/cloud-init-output.log "gs://beam-core-outputs/$cloud_init_output_path"
fi


#shutdown instance
sudo shutdown -h +"$SHUTDOWN_WAIT"
