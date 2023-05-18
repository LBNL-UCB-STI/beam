#!/bin/bash

required_variables_from_outside=(
  BEAM_BRANCH_NAME BEAM_COMMIT_SHA BEAM_DATA_BRANCH_NAME BEAM_DATA_COMMIT_SHA
  INSTANCE_ID INSTANCE_TYPE HOST_NAME BEAM_CONFIG TITLED MAX_RAM
  S3_PUBLISH S3_REGION
  WEB_BROWSER INSTANCE_REGION
  SHUTDOWN_WAIT PROFILER
  SIGOPT_CLIENT_ID SIGOPT_DEV_ID  # TODO maybe completely remove
)
echo "Following variables might be set only outside of the image (variable name -> 'current value'):"
for v in "${required_variables_from_outside[@]}" ; do
  vval1="$v"
  vval="${!vval1}"
  echo "$v -> '$vval'"
done


if [[ -z "$MAX_RAM" ]]; then MAX_RAM="8"; echo "MAX_RAM was not set, using default value: '$MAX_RAM'"; fi


optional_variables_from_outside=( PULL_CODE PULL_DATA SEND_NOTIFICATION )
echo "Following OPTIONAL variables might be set only outside of the image (variable name -> 'current value'):"
for v in "${optional_variables_from_outside[@]}" ; do
  vval1="$v"
  vval="${!vval1}"
  if [[ -z "$vval" ]]; then
    eval "$v=true"
    vval1="$v"
    vval="${!vval1}"
    echo "$v -> '$vval'  (using default value)"
  else
    echo "$v -> '$vval'"
  fi
done

echo "Started at $(date "+%Y-%m-%d-%H:%M:%S")"

# env

echo "Selected branch '$BEAM_BRANCH_NAME' with commit '$BEAM_COMMIT_SHA'"
echo "Selected data branch '$BEAM_DATA_BRANCH_NAME' with commit '$BEAM_DATA_COMMIT_SHA'"
echo "Selected config: '$BEAM_CONFIG'"
echo "S3 backup set to '$S3_PUBLISH' with region '$S3_REGION'"
echo "The title is '$TITLED'"
echo "The max ram is $MAX_RAM"


function send_slack_notification() {
  json_data="{\"text\":\"$1\"}"
  printf "\nSending the following json to slack:"
  echo "$json_data"
  echo " "
  curl -X POST -H 'Content-type:application/json' --data "$json_data" "$SLACK_HOOK_WITH_TOKEN"
}

function send_json_to_spreadsheet() {
  json_data="{\"command\":\"add\",\"type\":\"beam\",\"run\":{ $1 } }"
  printf "\nSending the following json to the spreadsheet:"
  echo "$json_data"
  echo " "
  curl -X POST -H 'Content-type:application/json' --data "$json_data" "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet"
}


# monitoring CPU | RAM usage
CPU_RAM_LOG="/app/sources/cpu_ram_usage.csv"
/app/write_cpu_ram_usage.sh > "$CPU_RAM_LOG" &


PATH_TO_PROJECT_PARENT="/app/sources"
cd "$PATH_TO_PROJECT_PARENT" || echo "ERROR: The path '$PATH_TO_PROJECT_PARENT' is not available"


# either pull the code or use the code from mounted folder
if [ "$PULL_CODE" = true ]; then
  BEAM_NAME="beam"
  echo "Pulling the code from github (PULL_CODE set to '$PULL_CODE'), cloning BEAM into $(pwd)/$BEAM_NAME"
  git clone --single-branch --branch "$BEAM_BRANCH_NAME" https://github.com/LBNL-UCB-STI/beam.git "$BEAM_NAME"
  cd "$BEAM_NAME" || echo "ERROR: The dir '$BEAM_NAME' is not available"

  if [[ $BEAM_COMMIT_SHA ]]; then
    echo "Resetting the branch '$BEAM_BRANCH_NAME' to commit '$BEAM_COMMIT_SHA'"
    git reset --hard "$BEAM_COMMIT_SHA";
  else
    BEAM_COMMIT_SHA=$(git log -1 --pretty=format:%H)
    echo "Using the latest commit in the branch '$BEAM_BRANCH_NAME' - '$BEAM_COMMIT_SHA'"
  fi
else
  echo "Pulling the code is disabled (PULL_CODE set to '$PULL_CODE'), using code mounted to '$PATH_TO_PROJECT_PARENT'."
  BEAM_COMMIT_SHA=$(git log -1 --pretty=format:%H)
  BEAM_BRANCH_NAME=$(git symbolic-ref --short HEAD)
  echo "Using the latest commit in the branch '$BEAM_BRANCH_NAME' - '$BEAM_COMMIT_SHA'"
fi


# remember the BEAM working folder location
BEAM_PATH=$(pwd)
echo "Working from '$BEAM_PATH'"


# pulling data from github if enabled or checking if data location was mounted separately
if [ "$PULL_DATA" = true ]; then
  echo "Pulling the data from github (PULL_DATA set to '$PULL_DATA')."
  production_data_submodules=$(git submodule | awk '{ print $2 }')
  for i in $production_data_submodules;
    do
      if [[ $BEAM_CONFIG  == $i* ]];
      then
        echo "Loading remote production data for $i"
        git config "submodule.$i.branch" "$BEAM_DATA_BRANCH_NAME"
        git submodule update --init --remote "$i"
        cd "$i" || echo "ERROR: The path '$i' is not available"
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

  echo "Doing lfs pull"
  git lfs pull
else
  echo "Pulling the data from github is disabled (PULL_DATA set to '$PULL_DATA')."
  COMBINED_CONFIG_PATH="/app/data/${BEAM_CONFIG#*/}"
  echo "Trying combined path from data volume: '$COMBINED_CONFIG_PATH'"
  if [ -e "$COMBINED_CONFIG_PATH" ]; then
    BEAM_CONFIG=$COMBINED_CONFIG_PATH
    echo "File exist, using config from mounted data volume - '$BEAM_CONFIG'"
  else
    echo "File does not exist, going to use data from inside beam folder."
  fi
fi


if [ "$SEND_NOTIFICATION" = true ]; then
  send_slack_notification "Run Started
    Run Name $TITLED
    Instance ID $INSTANCE_ID
    Instance type $INSTANCE_TYPE
    Host name $HOST_NAME
    Web browser $WEB_BROWSER
    Branch $BEAM_BRANCH_NAME
    Commit $BEAM_COMMIT_SHA"

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
    \"region\":\"$INSTANCE_REGION\",
    \"batch\":\"\",
    \"s3_link\":\"\",
    \"max_ram\":\"$MAX_RAM\",
    \"profiler_type\":\"$PROFILER\",
    \"config_file\":\"$BEAM_CONFIG\",
    \"sigopt_client_id\":\"$SIGOPT_CLIENT_ID\",
    \"sigopt_dev_id\":\"$SIGOPT_DEV_ID\""
else
  echo "Sending notifications is disabled (SEND_NOTIFICATION set to '$SEND_NOTIFICATION')."
fi


# calculating a location for gradle cache
if [[ -z "$GRADLE_CACHE_PATH" ]]; then
  GRADLE_CACHE_PATH="$BEAM_PATH/.gradle"
  echo "GRADLE_CACHE_PATH is not set, creating and using directory by path '$GRADLE_CACHE_PATH'"
  mkdir -p "$GRADLE_CACHE_PATH"
else
  echo "GRADLE_CACHE_PATH set, using directory by path '$GRADLE_CACHE_PATH'"
fi


## we shouldn't use the gradle daemon on NERSC, it seems that it's somehow shared within different nodes
## and all the subsequent runs have output dir somewhere else.
./gradlew --no-daemon --gradle-user-home="$GRADLE_CACHE_PATH" clean :run -PappArgs="['--config', '$BEAM_CONFIG']" -PmaxRAM="$MAX_RAM"


## Calculate the final status of simulation
log_file="$(find "$BEAM_PATH/output" -maxdepth 2 -mindepth 2 -type d -print -quit)/beamLog.out"
if [[ ! -f $log_file ]]; then
    echo "Unable to locate the beamLog.out file"
    final_status="Unable to start"
else
  last_line=$(tail "$log_file" -n 1)
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


# looking for output
sleep 10s
FINAL_PATH=""
for file in output/*; do
   for path2 in "$file"/*; do
     FINAL_PATH="$path2";
   done;
done;
echo "Found output dir: $FINAL_PATH"


echo "Moving debug files to output folder"
for file in "$BEAM_PATH"/*.jfr; do
  [ -e "$file" ] || continue
  echo "Zipping $file"
  zip "$file.zip" "$file"
  mv "$file.zip" "$FINAL_PATH"
done;
mv "$BEAM_PATH"/gc_* "$FINAL_PATH"
gzip "$CPU_RAM_LOG"
mv "$CPU_RAM_LOG.gz" "$FINAL_PATH"
chmod 777 -R "$FINAL_PATH"

# uploading output to s3 if enabled
if [ "$S3_PUBLISH" = true ]; then
  aws --region "$S3_REGION" s3 cp "$FINAL_PATH" s3://beam-outputs/"$FINAL_PATH" --recursive;
  s3output_url="https://s3.$S3_REGION.amazonaws.com/beam-outputs/index.html#$FINAL_PATH"
  SIMULATION_OUTPUT_LINK="$s3output_url"
  echo "Uploaded to $s3output_url"
else
  echo "S3 publishing is disabled (S3_PUBLISH set to '$S3_PUBLISH')."
fi


if [ "$SEND_NOTIFICATION" = true ]; then
  send_slack_notification "Run Completed
    Run Name $TITLED
    Instance ID $INSTANCE_ID
    Instance type $INSTANCE_TYPE
    Host name $HOST_NAME
    Web browser $WEB_BROWSER
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
    \"region\":\"$INSTANCE_REGION\",
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
else
  echo "Sending notifications is disabled (SEND_NOTIFICATION set to '$SEND_NOTIFICATION')."
fi


echo ""
echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
