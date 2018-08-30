#!/usr/bin/env bash
# targeted only for jenkins environment, as it lookup for some environment variables
# and these are only jenkins server environment.
# Also update GLIP_URL with glip webhook url to work script properly.

JSON_RESULT=$(curl --silent ${BUILD_URL}api/json)
BUILD_STATUS=$(echo $JSON_RESULT | jq -r '.result')
GIT_LOG=$(echo $JSON_RESULT | jq -r '.changeSet.items[]? | "[" + .id[0:7] + "](https://github.com/LBNL-UCB-STI/beam/commit/" + .id + "): "+ .msg + " - " + .author.fullName + "\n"')

if [ "$BUILD_STATUS" == "SUCCESS" ]
then
  status="Succeed"
else
  status="Failed"
fi
echo $status

icon="https://wiki.jenkins.io/download/attachments/2916393/logo.png"
activity="Build was $status"
title="[Build #${BUILD_NUMBER}](${BUILD_URL}) for **${JOB_BASE_NAME}** was $status"
body="**Branch:** [${GIT_BRANCH}](${GIT_URL})  \n **Changeset:** \n ${GIT_LOG} \n\n Follow these links for [change set](${RUN_CHANGES_DISPLAY_URL}) and [output log](${RUN_DISPLAY_URL})"

payload="\"icon\":\"${icon//\"/\\\"}\",\"activity\":\"${activity//\"/\\\"}\",\"title\":\"${title//\"/\\\"}\",\"body\":\"${body//\"/\\\"}\""

curl -H 'Content-Type: application/json' -d "{${payload}}" ${GLIP_URL}

