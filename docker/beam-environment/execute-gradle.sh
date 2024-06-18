#!/bin/bash

##
## logging CPU | RAM usage during simulation
##
cpu_ram_log="/root/sources/cpu_ram_usage.csv"
echo "CPU and RAM usage logging started, the output: 'cpu_ram_usage.csv'"
/app/write_cpu_ram_usage.sh > "$cpu_ram_log" &


if [ -n "$START_SSH_WITH_GRADLE_COMMAND" ]; then
  echo "Starting open-ssh because START_SSH_WITH_GRADLE_COMMAND is set to '$START_SSH_WITH_GRADLE_COMMAND'"
  /usr/sbin/sshd -D &
  echo "Open ssh started"
fi


##
## production configs usually require common folder being at location ../common
## one way of doing it - to copy common content from <code>/production/common to /root/common
##
PATH_TO_DATA="/root/data"
PATH_TO_CODE="/root/sources"
echo "Copy content of $PATH_TO_CODE/production/common to /root/common"
cp -R $PATH_TO_CODE/production/common/* /root/common/


##
## working inside code folder
##
cd "$PATH_TO_CODE" || echo "ERROR: the path '$PATH_TO_CODE' is not available"


##
## in case something is wrong:
if [ "$DEBUG" = true ]; then
  echo ""
  echo "'DEBUG' set to '$DEBUG', printing out different folders content."
  echo ".. content of /root/*"
  ls /root/*
  echo ""
fi


##
## calculating a location for gradle cache
## the file put in there in Dockefile, so, if anything mounted - there will be no file.
##
gradle_cache_path="$PATH_TO_CODE/.gradle"
if [ -e "/app/gradle_cache/.this_volume_was_not_mounted.txt" ]; then
  echo "Gradle cache was not mounted into /app/gradle_cache, using a default directory in the root of code folder: '.gradle'"
  mkdir -p "$gradle_cache_path"
else
  gradle_cache_path="/root/gradle_cache"
  echo "Gradle cache was mounted to /root/gradle_cache, going to use it."
fi


##
## fixing path to config file inside arguments
## the python script will try to use different combinations of paths
## the new arguments will be returned with changed path
##
provided_args="$*"
arguments_with_fixed_path_to_config=$(python3 /app/replace_config_path_in_args.py "$provided_args" "$PATH_TO_DATA")
arguments_fixed_completely=$(python3 /app/fix_quotes_for_app_args.py "$arguments_with_fixed_path_to_config")

##
## Executing gradle command, 'gradlew' should be executable.
## There should be no additional quotes around arguments, the arguments have to be split.
## Using eval to correctly provide arguments to command.
##
chmod +x gradlew
gradle_command="./gradlew --no-daemon --gradle-user-home=\"$gradle_cache_path\" $arguments_fixed_completely"
echo "Executing gradle command as $(whoami) from $(pwd):"
echo "$gradle_command"
echo ""
eval "$gradle_command"

echo "Execution complete."
