echo "Starting entrypoint script, at $(date "+%Y-%m-%d-%H:%M:%S")"
command=$1

if [ -n "$command" ]; then
  echo "Recognized command '$command'"
fi

print_help() {
  echo ""
  echo "First argument provided will be used as a command, the rest arguments will be used as parameters for the command."
  echo "Available commands: 'run_pilates' 'bash' 'help'."
  echo ""
}

case "$command" in
"sh" | "bash" | "/bin/bash" | "/bash") /bin/bash "${@:2}" ;;
"run_pilates") /usr/local/bin/run_docker_and_execute_pilates.sh ;;
"help" | "?") print_help ;;
*) print_help  ;;
esac

echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
