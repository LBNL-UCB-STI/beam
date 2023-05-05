#!/bin/bash

TITLED="??"
INSTANCE_ID="??"
INSTANCE_TYPE="??"
HOST_NAME="??"
WEB_BROWSER="??" # i.e. "http://$HOST_NAME:8000"
REGION="??"
SHUTDOWN_WAIT="??"

SIMULATION_OUTPUT_LINK="TODO" ## the link to be able to view progress of simulation

MAX_RAM="??"
PROFILER="??"
SIGOPT_CLIENT_ID="??"
SIGOPT_DEV_ID="??"


echo "Selected branch '$BEAM_BRANCH_NAME' with commit '$BEAM_COMMIT_SHA'"
echo "Selected data branch '$BEAM_DATA_BRANCH_NAME' with commit '$BEAM_DATA_COMMIT_SHA'"
echo "Selected config: '$BEAM_CONFIG'"
echo "S3 backup set to '$S3_PUBLISH' with region '$S3_REGION'"
echo "The title is '$TITLED'"

function send_slack_notification() {
  json_data="{\"text\":\"$1\"}"
  printf "\nSending the following json to slack:"
  echo "$json_data"
  echo " "
  curl -X POST -H 'Content-type:application/json' --data "$json_data" $SLACK_HOOK_WITH_TOKEN
}

function send_json_to_spreadsheet() {
  json_data="{\"command\":\"add\",\"type\":\"beam\",\"run\":{ $1 } }"
  printf "\nSending the following json to the spreadsheet:"
  echo "$json_data"
  echo " "
  curl -X POST -H 'Content-type:application/json' --data "$json_data" "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet"
}

/app/write_cpu_ram_usage.sh > /app/sources/cpu_ram_usage.csv &

PATH_TO_PROJECT_PARENT="/app/sources"

if [[ -z "$GRADLE_CACHE_PATH" ]]; then
  GRADLE_CACHE_PATH="$PATH_TO_PROJECT_PARENT/gradle_cache"
  echo "GRADLE_CACHE_PATH is not set, creating a directory by path '$GRADLE_CACHE_PATH'"
  mkdir -p "$GRADLE_CACHE_PATH"
fi

echo "Gradle cache will be at '$GRADLE_CACHE_PATH'"

cd "$PATH_TO_PROJECT_PARENT" || echo "ERROR: The path '$PATH_TO_PROJECT_PARENT' is not available"
echo "Cloning BEAM into $PWD/beam"
git clone --single-branch --branch $BEAM_BRANCH_NAME https://github.com/LBNL-UCB-STI/beam.git

cd beam || echo "ERROR: The path 'beam' is not available"

if [[ $BEAM_COMMIT_SHA ]]; then
  echo "Resetting the branch '$BEAM_BRANCH_NAME' to commit '$BEAM_COMMIT_SHA'"
  git reset --hard $BEAM_COMMIT_SHA;
else
  BEAM_COMMIT_SHA=$(git log -1 --pretty=format:%H)
  echo "Using the latest commit in the branch '$BEAM_BRANCH_NAME' - '$BEAM_COMMIT_SHA'"
fi

production_data_submodules=$(git submodule | awk '{ print $2 }')
for i in $production_data_submodules;
  do
    if [[ $BEAM_CONFIG  == $i* ]];
    then
      echo "Loading remote production data for $i"
      git config submodule.$i.branch $BEAM_DATA_BRANCH_NAME
      git submodule update --init --remote $i
      cd $i || echo "ERROR: The path '$i' is not available"
      if [[ $BEAM_DATA_COMMIT_SHA ]]; then
        echo "Checking out the data commit '$BEAM_DATA_COMMIT_SHA'"
        git checkout "$BEAM_DATA_COMMIT_SHA"
      else
        BEAM_DATA_COMMIT_SHA=$(git log -1 --pretty=format:%H)
        echo "Latest commit is '$BEAM_DATA_COMMIT_SHA'"
      fi
      cd - || echo "ERROR: Can't move to the previous location"
    fi;
  done

git lfs pull

send_slack_notification "Run Started
  Run Name $TITLED
  Instance ID $INSTANCE_ID
  Instance type $INSTANCE_TYPE
  Host name $HOST_NAME
  Web browser $WEB_BROWSER
  Region $REGION
  Branch $BEAM_BRANCH_NAME
  Commit $BEAM_COMMIT_SHA"

# shellcheck disable=SC2089
send_json_to_spreadsheet "\"status\":\"Run Started\",
  \"name\":\"$TITLED\",
  \"instance_id\":\"$INSTANCE_ID\",
  \"instance_type\":\"$INSTANCE_TYPE\",
  \"host_name\":\"$HOST_NAME\",
  \"browser\":\"$WEB_BROWSER\",
  \"branch\":\"$BEAM_BRANCH_NAME\",
  \"commit\":\"$BEAM_COMMIT_SHA\",
  \"data_branch\":\"$BEAM_DATA_BRANCH_NAME\",
  \"data_commit\":\"$BEAM_DATA_COMMIT_SHA\",
  \"region\":\"$REGION\",
  \"batch\":\"\",
  \"s3_link\":\"$SIMULATION_OUTPUT_LINK\",
  \"max_ram\":\"$MAX_RAM\",
  \"profiler_type\":\"$PROFILER\",
  \"config_file\":\"$BEAM_CONFIG\",
  \"sigopt_client_id\":\"$SIGOPT_CLIENT_ID\",
  \"sigopt_dev_id\":\"$SIGOPT_DEV_ID\""


## we shouldn't use the gradle daemon on NERSC, it seems that it's somehow shared within different nodes
## and all the subsequent runs have output dir somewhere else.
./gradlew --no-daemon --gradle-user-home="$GRADLE_CACHE_PATH" clean :run -PappArgs="['--config', '$BEAM_CONFIG']"

## Calculate the final status of simulation
log_file="$(find "$PATH_TO_PROJECT_PARENT"/beam/output -maxdepth 2 -mindepth 2 -type d -print -quit)/beamLog.out"
if [[ ! -f $log_file ]]; then
    echo "Unable to locate the beamLog.out file"
    final_status="Unable to start"
else
  last_line=$(tail $log_file -n 1)
  if [[ $last_line == *"Exiting BEAM"* ]]; then
      final_status="Run Completed"
  else
      final_status="Run Failed"
  fi
fi
echo "The final status of simulation is '$final_status'"


## TODO calculate health metrics
health_metrics_for_slack_notification="TODO"
stacktrace="TODO"
died_actors="TODO"
error="TODO"
warning="TODO"


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
  for file in "$PATH_TO_PROJECT_PARENT"/beam/*.jfr; do
    [ -e "$file" ] || continue
    echo "Zipping $file"
    zip "$file.zip" "$file"
    cp "$file.zip" "$finalPath"
  done;
  cp "$PATH_TO_PROJECT_PARENT"/beam/gc_* "$finalPath"
  aws --region "$S3_REGION" s3 cp "$finalPath" s3://beam-outputs/"$finalPath" --recursive;
  s3output_url="https://s3.$S3_REGION.amazonaws.com/beam-outputs/index.html#$finalPath"
  echo "Uploaded to $s3output_url"
else
  echo "S3 publishing disabled"
fi


send_slack_notification "Run Completed
  Run Name $TITLED
  Instance ID $INSTANCE_ID
  Instance type $INSTANCE_TYPE
  Host name $HOST_NAME
  Web browser $WEB_BROWSER
  Region $REGION
  Branch $BEAM_BRANCH_NAME
  Commit $BEAM_COMMIT_SHA
  Health Metrics $health_metrics_for_slack_notification
  S3 output url $s3output_url
  Shutdown in $SHUTDOWN_WAIT minutes"


# shellcheck disable=SC2089
send_json_to_spreadsheet "\"status\":\"$final_status\",
  \"name\":\"$TITLED\",
  \"instance_id\":\"$INSTANCE_ID\",
  \"instance_type\":\"$INSTANCE_TYPE\",
  \"host_name\":\"$HOST_NAME\",
  \"browser\":\"$WEB_BROWSER\",
  \"branch\":\"$BEAM_BRANCH_NAME\",
  \"commit\":\"$BEAM_COMMIT_SHA\",
  \"data_branch\":\"$BEAM_DATA_BRANCH_NAME\",
  \"data_commit\":\"$BEAM_DATA_COMMIT_SHA\",
  \"region\":\"$REGION\",
  \"batch\":\"\",
  \"s3_link\":\"$SIMULATION_OUTPUT_LINK\",
  \"max_ram\":\"$MAX_RAM\",
  \"profiler_type\":\"$PROFILER\",
  \"stacktrace\":\"$stacktrace\",
  \"died_actors\":\"$died_actors\",
  \"error\":\"$error\",
  \"warning\":\"$warning\",
  \"config_file\":\"$BEAM_CONFIG\",
  \"sigopt_client_id\":\"$SIGOPT_CLIENT_ID\",
  \"sigopt_dev_id\":\"$SIGOPT_DEV_ID\""

printf '\n script completed.'