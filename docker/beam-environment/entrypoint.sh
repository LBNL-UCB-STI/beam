#!/bin/bash

echo "Starting entrypoint script, at $(date "+%Y-%m-%d-%H:%M:%S")"
command=$1
echo "Recognized command '$command'"

print_help() {
  echo ""
  echo "First argument provided will be used as a command, the rest arguments will be used as parameters for the command."
  echo "Available commands: 'gradle' 'git' 'bash' 'ssh'."
  echo ""
}

print_unexpected() {
  echo ""
  echo "ERROR: Unexpected command: '$command'"
  print_help
}


case "$command" in
"ssh") /usr/sbin/sshd -D ;;
"git") /app/execute-git.sh "${@:2}" ;;
"gradle") /app/execute-gradle.sh "${@:2}" ;;
"slack") /app/execute-message-slack.sh "${@:2}" ;;
"sh" | "bash" | "/bin/bash" | "/bash") /bin/bash "${@:2}" ;;
"execute-beam-automatically") /app/execute-beam-automatically.sh ;;
"help" | "?") print_help ;;
*) print_unexpected  ;;
esac


echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
