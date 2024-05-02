echo "Starting entrypoint script, at $(date "+%Y-%m-%d-%H:%M:%S")"
command=$1

if [ -n "$command" ]; then
  echo "Recognized command '$command'"
fi

print_help() {
  echo ""
  echo "First argument provided will be used as a command, the rest arguments will be used as parameters for the command."
  echo "Available commands: 'bash' 'ssh', 'run_shell_script' or 'pilates'."
  echo ""
}

add_to_root_profile() {
  echo "adding '$1' to /root/.profile"
  echo "$1" >>/root/.profile
}

add_to_root_bashrc() {
  echo "adding '$1' to /root/.bashrc"
  echo "$1" >>/root/.bashrc
}

prepare_pilates() {
  echo "Preparing container to run pilates..."

  if [ -z "$PILATES_FOLDER" ]; then PILATES_FOLDER="/app/pilates"; fi
  if [ -z "$SHARED_FOLDER" ]; then SHARED_FOLDER="/app/shared"; fi

  add_to_root_profile "export PILATES=$PILATES_FOLDER"
  add_to_root_profile "export SHARED=$SHARED_FOLDER"
  add_to_root_profile "cd \$PILATES"
  add_to_root_profile "micromamba activate"
  add_to_root_profile "echo \"\$(python --version) is available from miniconda.\"; echo \"\""
  add_to_root_profile "echo \"PILATES is in \$PILATES (path is stored in PILATES environment variable)\""
  add_to_root_profile "echo \"Please do not move PILATES from its original folder, docker calls inside PILATES will break if run from different place.\"; echo \"\""
  add_to_root_profile "echo \"Shared folder is mounted to \$SHARED (path is stored in SHARED environment variable)\"; echo \"\""
  add_to_root_profile "echo \"In order to stop this container you could do 'docker ps' and then use 'docker stop <ID>' with id of this container.\""
  add_to_root_profile "echo \"Otherwise you could stop or cancel the job.\"; echo \"\""
  add_to_root_profile "echo \"If this container is prepared to run PILATES manually then in order to prepare the output folder\""
  add_to_root_profile "echo \"one could run '/usr/local/bin/prepare_pilates_output.sh' from inside PILATES folder.\"; echo \"\""

  micromamba shell init --shell bash --root-prefix=/opt/conda
  sudo service docker start
  echo "Docker started"

  cd $PILATES_FOLDER || echo "Pilates folder ($PILATES_FOLDER) is not available!"
  echo "Container is ready to run PILATES"
}

run_sshd() {
  if [ -n "$PREPARE_PILATES" ]; then
    prepare_pilates
  fi

  echo "starting SSHD ..."
  /usr/sbin/sshd -D
  echo "SSHD started"
}

sleep_for() {
  echo "sleeping for $1"
  sleep "$1"
}

run_pilates() {
  echo "starting SSHD ..."
  /usr/sbin/sshd -D &
  echo "SSHD started in background"

  prepare_pilates

  echo "Preparing to run pilates ..."

  cd $PILATES_FOLDER || echo "Pilates folder ($PILATES_FOLDER) is not available!"

  echo "Current pwd: $(pwd)"
  echo "Files available: $(ls -lah)"

  echo "Running pilates: 'python3 run.py'"
  python3 run.py 2>&1 | tee -a "log_pilates_$(date +'%Y%m%d_%H%M%S').out"

  echo "Preparing output folder"
  /usr/local/bin/prepare_pilates_output.sh
  echo "Done"

  if [ -n "$SLEEP_TIMEOUT_AFTER_PILATES" ]; then
    sleep_for "$SLEEP_TIMEOUT_AFTER_PILATES"
  else
    sleep_for "30m"
  fi
}

run_bash() {
  set -x
  if [ -n "$*" ]; then
    /bin/bash -c "$*"
  else
    /bin/bash
  fi
  set +x
}

run_shell_script() {
  if [ -z "$1" ]; then
    echo "An argument with path to shell script should be provided!"
  else
    if [ -n "$SHELL_SCRIPT_HOME" ]; then
      cd "$SHELL_SCRIPT_HOME" || echo "The path '$SHELL_SCRIPT_HOME' is not available."
    elif [ -n "$PILATES_FOLDER" ]; then
      cd "$PILATES_FOLDER" || echo "The path '$PILATES_FOLDER' is not available."
    else
      echo "Not PILATES_FOLDER nor SHELL_SCRIPT_HOME environment variables are set, using default location."
    fi
    echo "Executing '$1' from '$(pwd)' ..."
    # shellcheck disable=SC1090
    source "$1"
  fi
}

echo "starting .. "
printenv >>/root/all_env.txt
add_to_root_profile "clear"

case "$command" in
"sh" | "bash" | "/bin/bash" | "/bash") run_bash "${@:2}" ;;
"run_shell_script") run_shell_script "$2" ;;
"pilates") run_pilates ;;
"ssh") run_sshd ;;
*) print_help ;;
esac

echo "Completed command '$command' at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
