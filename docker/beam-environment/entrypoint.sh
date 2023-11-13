#!/bin/bash

echo "Starting entrypoint script, at $(date "+%Y-%m-%d-%H:%M:%S")"
command=$1
echo "Recognized command '$command'"


print_help() {
  echo ""
  echo "First argument provided will be used as a command, the rest arguments will be used as parameters for the command."
  echo ""
}

print_unexpected() {
  echo ""
  echo "ERROR: Unexpected command: '$command'"
  echo ""
}


case "$command" in
"gradle") /app/execute-gradle.sh "${@:2}" ;;
"git") /app/execute-git.sh "${@:2}" ;;
"sh" | "bash") /app/execute-sh.sh "${@:2}" ;;
"slack") /app/execute-message-slack.sh "${@:2}" ;;
"help" | "?") print_help ;;
*) print_unexpected  ;;
esac


echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
