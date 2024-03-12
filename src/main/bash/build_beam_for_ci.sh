#!/bin/bash

[[ -z "$BEAM_DOCKER_NAME" ]] && { echo "ERROR: environment variable BEAM_DOCKER_NAME is not set." ; exit 1; }
[[ -z "$NUMBER_OF_THREADS" ]] && { echo "ERROR: environment variable NUMBER_OF_THREADS is not set." ; exit 1; }

log_name="script_execution_$(date +"%Y-%m-%d_%H-%M-%S").log"
log(){
  msg=$1
  echo "$msg" | tee -a "$log_name"
}

run_gradle(){
  command=$1
  log "running command $command ..."
  docker run -v "$(pwd)":/root/sources "$BEAM_DOCKER_NAME" "gradle" "$command" | tee -a "$log_name"

  if [[ $? -eq 0 ]]; then
      log "Tests passed. Do something."
  else
      log "Tests didn't pass. Do something."
      exit 1
  fi

  log "command $command completed."
}

run_gradle "scalaFmtAll --stacktrace"
run_gradle "checkScalaFmt --stacktrace"
run_gradle "verifyScalaFmtHasBeenRun --stacktrace"
run_gradle "build -PnumThreads=$NUMBER_OF_THREADS --info"