#!/bin/bash


##
## working only variables were set
##
if [[ -z "$SLACK_HOOK_WITH_TOKEN" ]]; then
  echo "WARNING: Can't send notifications to slack, SLACK_HOOK_WITH_TOKEN is not set!"
elif [[ -z "$1" ]]; then
  echo "WARNING: Can't send notifications to slack, the message (second argument) was not provided!"
else
  json_data="{\"text\":\"$1\"}"
  printf "\nSending the following json to slack:"
  echo "$json_data"
  echo " "
  curl -X POST -H 'Content-type:application/json' --data "$json_data" "$SLACK_HOOK_WITH_TOKEN"
  echo " "
fi

