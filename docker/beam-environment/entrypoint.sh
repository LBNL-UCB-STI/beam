#!/bin/bash

echo "Starting entrypoint script, at $(date "+%Y-%m-%d-%H:%M:%S")"

function print_error(){
  echo ""
  echo "ERROR!"
  echo "ERROR!"
  echo "ERROR!"
  echo "$1"
  echo ""
}

##
## print variables that might be set only outside of image
##
required_variables_from_outside=( BEAM_CONFIG MAX_RAM )
echo "Following variables are required for BEAM to work (variable name -> 'current value'):"
for v in "${required_variables_from_outside[@]}" ; do
  vval1="$v"
  vval="${!vval1}"
  echo "$v -> '$vval'"

  if [ -z "$vval" ]; then
    print_error "'$v' environment variable is required for BEAM to work, please, specify a value for it."
    exit 1
  fi
done


##
## print optional variables that might be set only outside of image
##
optional_variables_from_outside=(
  BEAM_COMMIT_SHA BEAM_DATA_COMMIT_SHA
  BEAM_BRANCH_NAME BEAM_DATA_BRANCH_NAME
  PULL_CODE PULL_DATA
  ENFORCE_HTTPS_FOR_DATA_REPOSITORY
  S3_PUBLISH AWS_SECRET_ACCESS_KEY AWS_ACCESS_KEY_ID S3_REGION
  PROFILER
  SIMULATION_HOST_LOG_FILE
  GRADLE_CACHE_PATH
  NOTIFICATION_SEND
  SIMULATIONS_SPREADSHEET_UPDATE_URL SLACK_HOOK_WITH_TOKEN
  NOTIFICATION_TITLED NOTIFICATION_SHUTDOWN_WAIT
  NOTIFICATION_INSTANCE_ID NOTIFICATION_INSTANCE_TYPE NOTIFICATION_HOST_NAME
  NOTIFICATION_WEB_BROWSER NOTIFICATION_INSTANCE_REGION
  NOTIFICATION_SIGOPT_CLIENT_ID NOTIFICATION_SIGOPT_DEV_ID
)
echo "Following variables are optional (variable name -> 'current value'):"
for v in "${optional_variables_from_outside[@]}" ; do
  vval1="$v"
  vval="${!vval1}"
  echo "$v -> '$vval'"
done


function send_slack_notification() {
  if [[ -z "$SLACK_HOOK_WITH_TOKEN" ]]; then
    echo "WARNING: Can't send notifications to slack, SLACK_HOOK_WITH_TOKEN is not set!"
  else
    json_data="{\"text\":\"$1\"}"
    printf "\nSending the following json to slack:"
    echo "$json_data"
    echo " "
    curl -X POST -H 'Content-type:application/json' --data "$json_data" "$SLACK_HOOK_WITH_TOKEN"
    echo " "
  fi
}

function send_json_to_spreadsheet() {
  if [[ -z "$SIMULATIONS_SPREADSHEET_UPDATE_URL" ]]; then
    echo "WARNING: Can't send updates to the spreadsheet, SIMULATIONS_SPREADSHEET_UPDATE_URL is not set!"
  else
    json_data="{\"command\":\"add\",\"type\":\"beam\",\"run\":{ $1 } }"
    printf "\nSending the following json to the spreadsheet:"
    echo "$json_data"
    echo " "
    curl -X POST -H 'Content-type:application/json' --data "$json_data" "$SIMULATIONS_SPREADSHEET_UPDATE_URL"
    echo " "
  fi
}


##
## location to project folder
##
path_to_project_parent="/app/sources"
cd "$path_to_project_parent" || echo "ERROR: The path '$path_to_project_parent' is not available"


##
## either pull the code or use the code from mounted folder
##
if [ "$PULL_CODE" = true ]; then
  if [ -z "$BEAM_BRANCH_NAME" ]; then
    print_error "PULL_CODE set to true, but BEAM_BRANCH_NAME is empty! Please, specify both."
    exit 1
  fi

  #  beam_name="beam"
  beam_code_url="https://github.com/LBNL-UCB-STI/beam.git"
  echo "Pulling the code from github (PULL_CODE set to '$PULL_CODE'), cloning '$beam_code_url' into mounted folder"
  git clone --single-branch --branch "$BEAM_BRANCH_NAME" "$beam_code_url" . || exit 1
  #  cd "$beam_name" || echo "ERROR: The dir '$beam_name' is not available"

  if [[ $BEAM_COMMIT_SHA ]]; then
    echo "Resetting the code to commit '$BEAM_COMMIT_SHA'"
    git reset --hard "$BEAM_COMMIT_SHA";
  else
    BEAM_COMMIT_SHA=$(git log -1 --pretty=format:%H)
    echo "Using the latest commit in the branch '$BEAM_BRANCH_NAME' - '$BEAM_COMMIT_SHA'"
  fi
else
  echo "Pulling the code is disabled (PULL_CODE set to '$PULL_CODE'), using code mounted to '$path_to_project_parent'."
  BEAM_COMMIT_SHA=$(git log -1 --pretty=format:%H)
  BEAM_BRANCH_NAME=$(git symbolic-ref --short HEAD)
  echo "Using the branch '$BEAM_BRANCH_NAME', commit '$BEAM_COMMIT_SHA'"
fi


##
## logging CPU | RAM usage during simulation
##
cpu_ram_log="/app/sources/cpu_ram_usage.csv"
/app/write_cpu_ram_usage.sh > "$cpu_ram_log" &


##
## Remember the BEAM working folder location.
## This is required to be able either pull the code or use existing code.
##
beam_path=$(pwd)
echo "Working from '$beam_path'"


##
## Required to avoid configuring ssh keys because by-default all our git data submodules configured to use ssh.
##
if [ "$ENFORCE_HTTPS_FOR_DATA_REPOSITORY" = true ]; then
  echo "Forcing git to use https over ssh\git url. In order to pull public repository without configuring ssh keys."
  git config --global url."https://github.com/LBNL-UCB-STI".insteadOf "git@github.com:LBNL-UCB-STI"
fi


##
## pulling data from github if enabled or checking if data location was mounted separately
##
if [ "$PULL_DATA" = true ]; then
  if [ -z "$BEAM_DATA_BRANCH_NAME" ]; then
    print_error "PULL_DATA set to true, but BEAM_DATA_BRANCH_NAME is empty! Please, specify both."
    exit 1
  fi

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
  combined_config_path1="/app/data/${BEAM_CONFIG#*/}"
  combined_config_path2="/app/sources/${BEAM_CONFIG}"
  echo "Trying combined path1 from data volume: '$combined_config_path1'"
  echo "Trying combined path2 from source volume: '$combined_config_path2'"
  if [ -e "$combined_config_path1" ]; then
    BEAM_CONFIG=$combined_config_path1
    echo "File by path1 exist, using absolute path to config from mounted data volume - '$BEAM_CONFIG'"
  elif [ -e "$combined_config_path2" ]; then
    echo "File by path2 exist, using relative path to config from source folder - '$BEAM_CONFIG'"
  else
    print_error "Unable to locate config by path '$BEAM_CONFIG', please specify correct path."
    exit 1
  fi
fi


##
## notification
##
if [ "$NOTIFICATION_SEND" = true ]; then
  send_slack_notification "Run Started
    Run Name $NOTIFICATION_TITLED
    Instance ID $NOTIFICATION_INSTANCE_ID
    Instance type $NOTIFICATION_INSTANCE_TYPE
    Host name $NOTIFICATION_HOST_NAME
    Web browser $NOTIFICATION_WEB_BROWSER
    Branch $BEAM_BRANCH_NAME
    Commit $BEAM_COMMIT_SHA"

  send_json_to_spreadsheet "\"status\":\"Run Started\",
    \"name\":\"$NOTIFICATION_TITLED\",
    \"instance_id\":\"$NOTIFICATION_INSTANCE_ID\",
    \"instance_type\":\"$NOTIFICATION_INSTANCE_TYPE\",
    \"host_name\":\"$NOTIFICATION_HOST_NAME\",
    \"browser\":\"$NOTIFICATION_WEB_BROWSER\",
    \"branch\":\"$BEAM_BRANCH_NAME\",
    \"commit\":\"$BEAM_COMMIT_SHA\",
    \"data_branch\":\"$BEAM_DATA_BRANCH_NAME\",
    \"data_commit\":\"$BEAM_DATA_COMMIT_SHA\",
    \"region\":\"$NOTIFICATION_INSTANCE_REGION\",
    \"batch\":\"\",
    \"s3_link\":\"\",
    \"max_ram\":\"$MAX_RAM\",
    \"profiler_type\":\"$PROFILER\",
    \"config_file\":\"$BEAM_CONFIG\",
    \"sigopt_client_id\":\"$NOTIFICATION_SIGOPT_CLIENT_ID\",
    \"sigopt_dev_id\":\"$NOTIFICATION_SIGOPT_DEV_ID\""
else
  echo "Sending notifications is disabled (NOTIFICATION_SEND set to '$NOTIFICATION_SEND')."
fi


##
## calculating a location for gradle cache
##
if [ -n "$GRADLE_CACHE_PATH" ]; then
  echo "GRADLE_CACHE_PATH set, using directory by path '$GRADLE_CACHE_PATH'"
  mkdir -p "$GRADLE_CACHE_PATH"
else
  GRADLE_CACHE_PATH="$beam_path/.gradle"
  echo "GRADLE_CACHE_PATH is not set, using directory by default path '$GRADLE_CACHE_PATH'"
  mkdir -p "$GRADLE_CACHE_PATH"
fi


##
## we shouldn't use the gradle daemon on NERSC, it seems that it's somehow shared within different nodes
## and all the subsequent runs have output dir somewhere else.
##
./gradlew --no-daemon --gradle-user-home="$GRADLE_CACHE_PATH" clean :run -PappArgs="['--config', '$BEAM_CONFIG']" -PmaxRAM="$MAX_RAM" -Pprofiler_type="$PROFILER"


##
## Calculate the final status of simulation
##
log_file="$(find "$beam_path/output" -maxdepth 2 -mindepth 2 -type d -print -quit)/beamLog.out"
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


##
## calculating the health of simulation
##
warn="?"
error="?"
actorDied="?"
stacktrace="?"
health_metrics=""
simulation_health_analysis_output_file="simulation_health_analysis_result.txt"
python3 src/main/python/general_analysis/simulation_health_analysis.py $simulation_health_analysis_output_file
while IFS="," read -r metric count; do
  export "$metric=$count"
  health_metrics="$health_metrics, $metric:$count"
done < $simulation_health_analysis_output_file
health_metrics="$(echo "$health_metrics" | cut -c3-)"
echo "Health metrics: '$health_metrics'"


##
## Working with output of simulation
##
sleep 10s
FINAL_PATH=""
for file in output/*; do
   for path2 in "$file"/*; do
     FINAL_PATH="$path2";
   done;
done;
echo "Found output dir: $FINAL_PATH"

echo "Moving debug files to output folder."
for file in "$beam_path"/*.jfr; do
  [ -e "$file" ] || continue
  echo "Zipping $file"
  zip "$file.zip" "$file"
  mv "$file.zip" "$FINAL_PATH"
done;

echo "Moving health metrics."
mv "$simulation_health_analysis_output_file" "$FINAL_PATH"

echo "Moving garbage collection logs."
mv "$beam_path"/gc_* "$FINAL_PATH"

echo "Moving CPU and RAM used/available collected during simulation."
gzip "$cpu_ram_log" && mv "$cpu_ram_log"* "$FINAL_PATH"

if [ -n "$SIMULATION_HOST_LOG_FILE" ]; then
  echo "Copy log of simulation from host computer (cloud-init log or analog)."
  cp "$SIMULATION_HOST_LOG_FILE" "$FINAL_PATH"
else
  echo "Path to host computer simulation log file (cloud-init log or analog) was not provided."
fi

echo "Fixing permission issues related to access to files created from inside of image."
chmod 777 -R "$FINAL_PATH"


##
## uploading output to s3 if enabled
##
if [ "$S3_PUBLISH" = true ]; then
  aws --region "$S3_REGION" s3 cp "$FINAL_PATH" s3://beam-outputs/"$FINAL_PATH" --recursive;
  s3output_url="https://s3.$S3_REGION.amazonaws.com/beam-outputs/index.html#$FINAL_PATH"
  simulation_output_link="$s3output_url"
  echo "Uploaded to $s3output_url"
else
  echo "S3 publishing is disabled (S3_PUBLISH set to '$S3_PUBLISH')."
fi


##
## notification
##
if [ "$NOTIFICATION_SEND" = true ]; then
  send_slack_notification "Run Completed
    Run Name $NOTIFICATION_TITLED
    Instance ID $NOTIFICATION_INSTANCE_ID
    Instance type $NOTIFICATION_INSTANCE_TYPE
    Host name $NOTIFICATION_HOST_NAME
    Web browser $NOTIFICATION_WEB_BROWSER
    Branch $BEAM_BRANCH_NAME
    Commit $BEAM_COMMIT_SHA
    Health Metrics $health_metrics
    S3 output url $s3output_url
    Shutdown in $NOTIFICATION_SHUTDOWN_WAIT minutes"

  # shellcheck disable=SC2089
  send_json_to_spreadsheet "\"status\":\"$final_status\",
    \"name\":\"$NOTIFICATION_TITLED\",
    \"instance_id\":\"$NOTIFICATION_INSTANCE_ID\",
    \"instance_type\":\"$NOTIFICATION_INSTANCE_TYPE\",
    \"host_name\":\"$NOTIFICATION_HOST_NAME\",
    \"browser\":\"$NOTIFICATION_WEB_BROWSER\",
    \"branch\":\"$BEAM_BRANCH_NAME\",
    \"commit\":\"$BEAM_COMMIT_SHA\",
    \"data_branch\":\"$BEAM_DATA_BRANCH_NAME\",
    \"data_commit\":\"$BEAM_DATA_COMMIT_SHA\",
    \"region\":\"$NOTIFICATION_INSTANCE_REGION\",
    \"batch\":\"\",
    \"s3_link\":\"$simulation_output_link\",
    \"max_ram\":\"$MAX_RAM\",
    \"profiler_type\":\"$PROFILER\",
    \"stacktrace\":\"$stacktrace\",
    \"died_actors\":\"$actorDied\",
    \"error\":\"$error\",
    \"warning\":\"$warn\",
    \"config_file\":\"$BEAM_CONFIG\",
    \"sigopt_client_id\":\"$NOTIFICATION_SIGOPT_CLIENT_ID\",
    \"sigopt_dev_id\":\"$NOTIFICATION_SIGOPT_DEV_ID\""
else
  echo "Sending notifications is disabled (NOTIFICATION_SEND set to '$NOTIFICATION_SEND')."
fi


echo ""
echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
