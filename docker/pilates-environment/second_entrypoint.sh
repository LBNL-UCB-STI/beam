echo "Starting entrypoint script, at $(date "+%Y-%m-%d-%H:%M:%S")"
command=$1

if [ -n "$command" ]; then
  echo "Recognized command '$command'"
fi

print_help() {
  echo ""
  echo "First argument provided will be used as a command, the rest arguments will be used as parameters for the command."
  echo "Available commands: 'bash' 'ssh' 'help'."
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

activate_micromamba_and_run_ssh() {
  echo "starting .. "
  printenv >>/root/all_env.txt

  # ## in order to change terminal prompt string (but let's avoid it for now)
  # add_to_root_bashrc "export PS1=\"${debian_chroot:+($debian_chroot)}\u@\h:\W\$ \""

  if [ -z "$PILATES_FOLDER" ]; then PILATES_FOLDER="/app/pilates"; fi
  if [ -z "$SHARED_FOLDER" ]; then SHARED_FOLDER="/app/shared"; fi

  add_to_root_profile "clear"
  add_to_root_profile "export PILATES=$PILATES_FOLDER"
  add_to_root_profile "export SHARED=$SHARED_FOLDER"
  add_to_root_profile "cd \$PILATES"
  add_to_root_profile "micromamba activate"
  add_to_root_profile "echo \"\$(python --version) is available from miniconda.\"; echo \"\""
  add_to_root_profile "echo \"PILATES is copied in \$PILATES (path is stored in PILATES environment variable)\""
  add_to_root_profile "echo \"Please do not move PILATES from its original folder, some of PILATES parts will break if run from different place.\"; echo \"\""
  add_to_root_profile "echo \"Shared folder is mounted to \$SHARED (path is stored in SHARED environment variable)\"; echo \"\""
  add_to_root_profile "echo \"In order to stop this container you could do 'docker ps' and then use docker stop with id of this container.\""
  add_to_root_profile "echo \"Leaving with 'exit' will not stop this container, you could later just connect with ssh again.\"; echo \"\""

  micromamba shell init --shell bash --root-prefix=/opt/conda
  sudo service docker start
  echo "Docker started"

  if [ -n "$RUN_PILATES" ]; then
    cd $PILATES_FOLDER || echo "Pilates folder ($PILATES_FOLDER) is not available!"

    echo "Current pwd: $(pwd)"
    echo "Files available: $(ls -lah)"

    echo "Running pilates: 'python3 run.py'"
    python3 run.py 2>&1 | tee -a "pilates_execution_$(date +'%Y%m%d_%H%M%S').log" &
  fi

  /usr/sbin/sshd -D
}

case "$command" in
"sh" | "bash" | "/bin/bash" | "/bash") /bin/bash "${@:2}" ;;
"ssh") activate_micromamba_and_run_ssh ;;
"help" | "?") print_help ;;
*) print_help ;;
esac

echo "Completed at $(date "+%Y-%m-%d-%H:%M:%S")"
echo ""
