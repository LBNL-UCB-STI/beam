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

command=$1

print_help() {
  echo "First argument provided will be used as a command, the rest arguments will be used as parameters for the command."
}

case "$command" in
"gradle") /app/execute-gradle.sh "${@:2}" ;;
"help" | "?") print_help ;;
*) echo "ERROR: Unexpected command: '$command'" ;;
esac

# tail_args="${*:2}"
# echo "Arguments starting from the second: $tail_args"

echo ""
echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
