#!/bin/bash

# Multi-node BEAM launcher for Lawrencium.
# It allocates one Slurm job across multiple nodes, then runs one BEAM master
# process plus one BEAM worker process per remaining node.

BATCH_MODE_SENTINEL="RUN_CLUSTER_JOB_MODE"

if [[ "${!BATCH_MODE_SENTINEL:-submit}" != "batch" ]]; then
  echo "Starting the multi-node job .."

  export BEAM_BRANCH_NAME="${BEAM_BRANCH_NAME:-develop}"
  export BEAM_COMMIT_SHA="${BEAM_COMMIT_SHA:-}"
  export BEAM_DATA_BRANCH_NAME="${BEAM_DATA_BRANCH_NAME:-develop}"
  export BEAM_DATA_COMMIT_SHA="${BEAM_DATA_COMMIT_SHA:-}"
  export BEAM_CONFIG="${BEAM_CONFIG:-test/input/beamville/beam.conf}"
  export PROFILER="${PROFILER:-}"
  export BEAM_IMAGE_MODE="${BEAM_IMAGE_MODE:-clone}"

  export PULL_CODE="${PULL_CODE:-true}"
  export PULL_DATA="${PULL_DATA:-true}"

  PARTITION="${PARTITION:-lr5}"
  QOS="${QOS:-lr_normal}"
  MEMORY_LIMIT="${MEMORY_LIMIT:-60}"
  TOTAL_NODES="${3:-2}"
  AKKA_PORT="${AKKA_PORT:-25520}"

  export S3_REGION="us-east-2"
  export S3_PUBLISH="false"
  export AWS_SECRET_ACCESS_KEY=""
  export AWS_ACCESS_KEY_ID=""

  export SEND_NOTIFICATION="false"
  export SLACK_HOOK_WITH_TOKEN=""
  export SIMULATIONS_SPREADSHEET_UPDATE_URL=""

  ACCOUNT="${ACCOUNT:-ac_beamcore}"

  RUN_NAME="$1"
  EXPECTED_EXECUTION_DURATION="$2"

  if [[ -z "$RUN_NAME" ]]; then
    echo "Error: RUN_NAME is not set."
    exit 1
  fi

  if [[ -z "$EXPECTED_EXECUTION_DURATION" ]]; then
    echo "Error: EXPECTED_EXECUTION_DURATION is not set."
    exit 1
  fi

  if [[ "$TOTAL_NODES" -lt 2 ]]; then
    echo "Error: TOTAL_NODES must be at least 2 (1 master + 1 worker)."
    exit 1
  fi

  export MAX_RAM="$MEMORY_LIMIT"
  export NOTIFICATION_TITLED="$USER/$RUN_NAME"

  RANDOM_PART=$(tr -dc A-Z0-9 </dev/urandom | head -c 8)
  DATETIME=$(date "+%Y.%m.%d-%H.%M.%S")
  NAME_SUFFIX="$DATETIME.$RANDOM_PART.$PARTITION.$QOS.$MEMORY_LIMIT.cluster"

  BEAM_BASE_DIR="/global/scratch/users/$USER/out_beam_$NAME_SUFFIX"
  mkdir -p "$BEAM_BASE_DIR"
  SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

  JOB_LOG_FILE_NAME="cluster-log-file.log"
  JOB_LOG_FILE_PATH="$BEAM_BASE_DIR/$JOB_LOG_FILE_NAME"
  SLURM_STDOUT_PATH="$BEAM_BASE_DIR/slurm-batch.log"
  LINK_TO_JOB_LOG_FILE="$(pwd)/out.cluster.$NAME_SUFFIX.log"
  touch "$JOB_LOG_FILE_PATH"
  ln -snf "$JOB_LOG_FILE_PATH" "$LINK_TO_JOB_LOG_FILE"
  echo "Submitted from: $(date "+%Y-%m-%d-%H:%M:%S")" >>"$JOB_LOG_FILE_PATH"
  echo "Run directory: $BEAM_BASE_DIR" >>"$JOB_LOG_FILE_PATH"

  export JOB_LOG_FILE_PATH
  export LINK_TO_JOB_LOG_FILE
  export BEAM_BASE_DIR
  export AKKA_PORT

  JOB_NAME="$RANDOM_PART.$DATETIME.multi"
  BATCH_WRAPPER_PATH="$BEAM_BASE_DIR/run_cluster_job.batch.sh"
  cat >"$BATCH_WRAPPER_PATH" <<EOF
#!/bin/bash
set -euo pipefail
export ${BATCH_MODE_SENTINEL}=batch
export BEAM_BASE_DIR='$BEAM_BASE_DIR'
export JOB_LOG_FILE_PATH='$JOB_LOG_FILE_PATH'
export LINK_TO_JOB_LOG_FILE='$LINK_TO_JOB_LOG_FILE'
export AKKA_PORT='$AKKA_PORT'
export BEAM_IMAGE_MODE='${BEAM_IMAGE_MODE}'
export BEAM_CONFIG='${BEAM_CONFIG}'
export DOCKER_IMAGE_NAME='${DOCKER_IMAGE_NAME:-}'
export IMAGE_TAG='${IMAGE_TAG:-}'
export PREBUILT_SIF_PATH='${PREBUILT_SIF_PATH:-}'
touch "$BEAM_BASE_DIR/batch-wrapper-entered.txt"
exec >>"\$JOB_LOG_FILE_PATH" 2>&1
echo "Entered batch wrapper at \$(date "+%Y-%m-%d-%H:%M:%S")"
echo "Wrapper cwd: \$(pwd)"
echo "Wrapper script path: $SCRIPT_PATH"
exec "$SCRIPT_PATH"
EOF
  chmod +x "$BATCH_WRAPPER_PATH"

  set -x
  SBATCH_OUTPUT=$(
    sbatch --parsable \
      --partition="$PARTITION" \
      --exclusive \
      --nodes="$TOTAL_NODES" \
      --mem="${MEMORY_LIMIT}G" \
      --qos="$QOS" \
      --account="$ACCOUNT" \
      --export=ALL \
      --job-name="$JOB_NAME" \
      --output="$SLURM_STDOUT_PATH" \
      --time="$EXPECTED_EXECUTION_DURATION" \
      "$BATCH_WRAPPER_PATH"
  )
  set +x
  JOB_ID="${SBATCH_OUTPUT%%;*}"
  echo "$JOB_ID" >"$BEAM_BASE_DIR/slurm-job-id.txt"
  echo "Slurm job id: $JOB_ID" | tee -a "$JOB_LOG_FILE_PATH"
  echo "Track with: sacct -j $JOB_ID --format=JobID,JobName,State,ExitCode,Elapsed,NodeList" | tee -a "$JOB_LOG_FILE_PATH"

else
  set -euo pipefail

  if [[ -z "${BEAM_BASE_DIR:-}" ]]; then
    if [[ -n "${JOB_LOG_FILE_PATH:-}" ]]; then
      BEAM_BASE_DIR="$(dirname "$JOB_LOG_FILE_PATH")"
    else
      echo "Error: neither BEAM_BASE_DIR nor JOB_LOG_FILE_PATH is set"
      exit 1
    fi
  fi

  if [[ -z "${JOB_LOG_FILE_PATH:-}" ]]; then
    JOB_LOG_FILE_PATH="$BEAM_BASE_DIR/cluster-log-file.log"
  fi

  mkdir -p "$BEAM_BASE_DIR"
  touch "$JOB_LOG_FILE_PATH"
  exec >>"$JOB_LOG_FILE_PATH" 2>&1

  echo "Executing the multi-node job .."
  echo "Batch sentinel: ${!BATCH_MODE_SENTINEL}"
  echo "Run directory: $BEAM_BASE_DIR"
  echo "Image mode: ${BEAM_IMAGE_MODE:-clone}"
  echo "Config: ${BEAM_CONFIG:-<unset>}"
  echo "Started at: $(date "+%Y-%m-%d-%H:%M:%S")"

  export NOTIFICATION_INSTANCE_ID=$SLURMD_NODENAME
  export NOTIFICATION_INSTANCE_TYPE="Lawrencium $SLURM_JOB_PARTITION"
  export NOTIFICATION_HOST_NAME=$HOSTNAME
  export NOTIFICATION_WEB_BROWSER="TODO"
  export NOTIFICATION_INSTANCE_REGION=""
  export NOTIFICATION_SHUTDOWN_WAIT=""

  IMAGE_NAME="beam-environment"
  IMAGE_TAG="${IMAGE_TAG:-jdk-11-4.01}"
  DOCKER_IMAGE_NAME="${DOCKER_IMAGE_NAME:-docker://beammodel/${IMAGE_NAME}:${IMAGE_TAG}}"
  PREBUILT_SIF_PATH="${PREBUILT_SIF_PATH:-${SINGULARITY_IMAGE_PATH:-}}"
  if [[ -n "$PREBUILT_SIF_PATH" ]]; then
    SINGULARITY_IMAGE_PATH="$PREBUILT_SIF_PATH"
  else
    SINGULARITY_IMAGE_PATH="$BEAM_BASE_DIR/${IMAGE_NAME}_${IMAGE_TAG}.sif"
  fi
  export ENFORCE_HTTPS_FOR_DATA_REPOSITORY="true"

  mkdir -p "$BEAM_BASE_DIR/logs"

  if [[ -n "$PREBUILT_SIF_PATH" ]]; then
    if [[ ! -f "$SINGULARITY_IMAGE_PATH" ]]; then
      echo "Error: prebuilt SIF not found at '$SINGULARITY_IMAGE_PATH'"
      exit 1
    fi
    echo "Using prebuilt SIF '$SINGULARITY_IMAGE_PATH'"
  else
    echo "Pulling docker image '$DOCKER_IMAGE_NAME' to '$SINGULARITY_IMAGE_PATH' ..."
    set -x
    singularity pull --force "$SINGULARITY_IMAGE_PATH" "$DOCKER_IMAGE_NAME"
    set +x
  fi

  mapfile -t HOSTS < <(scontrol show hostnames "$SLURM_JOB_NODELIST")
  if [[ "${#HOSTS[@]}" -lt 2 ]]; then
    echo "Error: Expected at least 2 hosts in allocation, found ${#HOSTS[@]}"
    exit 1
  fi

  MASTER_HOST="${HOSTS[0]}"
  SEED_ADDRESS="${MASTER_HOST}:${AKKA_PORT}"
  EXPECTED_WORKER_NODES=$((${#HOSTS[@]} - 1))
  PIDS=()

  launch_role() {
    local host="$1"
    local role="$2"
    local role_dir="$3"
    local app_args="$4"
    local role_config_path="$5"

    mkdir -p "$role_dir"

    if [[ "$BEAM_IMAGE_MODE" == "baked" ]]; then
      srun --nodes=1 --ntasks=1 --exclusive -w "$host" \
        --output="$BEAM_BASE_DIR/logs/${role}-%N.log" \
        bash -lc "
          set -euo pipefail
          export PULL_CODE='false'
          export PULL_DATA='false'
          export GRADLE_CACHE_PATH='/root/data/.gradle'
          export BEAM_CONFIG='$role_config_path'
          export BEAM_APP_ARGS=\"$app_args\"
          singularity run --writable-tmpfs -B \"$role_dir:/root/data\" \"$SINGULARITY_IMAGE_PATH\"
        " &
    else
      srun --nodes=1 --ntasks=1 --exclusive -w "$host" \
        --output="$BEAM_BASE_DIR/logs/${role}-%N.log" \
        bash -lc "
          set -euo pipefail
          export BEAM_DIR='$role_dir'
          export BEAM_APP_ARGS=\"$app_args\"
          singularity run -B \"$role_dir:/root/sources\" \"$SINGULARITY_IMAGE_PATH\"
        " &
    fi
    PIDS+=($!)
  }

  MASTER_DIR="$BEAM_BASE_DIR/master"
  mkdir -p "$MASTER_DIR"
  MASTER_CONFIG="$MASTER_DIR/cluster-master.conf"
  cat >"$MASTER_CONFIG" <<EOF
include required(file("$BEAM_CONFIG"))

beam.cluster.expectedWorkerNodes = $EXPECTED_WORKER_NODES
beam.outputs.baseOutputDirectory = "/root/data/output"
EOF
  if [[ "$BEAM_IMAGE_MODE" == "baked" ]]; then
    MASTER_CONFIG_IN_CONTAINER="/root/data/cluster-master.conf"
  else
    MASTER_CONFIG_IN_CONTAINER="$MASTER_CONFIG"
  fi
  MASTER_ARGS="['--config', '$MASTER_CONFIG_IN_CONTAINER', '--cluster-type', 'master', '--node-host', '$MASTER_HOST', '--node-port', '$AKKA_PORT', '--seed-address', '$SEED_ADDRESS', '--use-local-worker', 'false']"
  launch_role "$MASTER_HOST" "master" "$MASTER_DIR" "$MASTER_ARGS" "$MASTER_CONFIG_IN_CONTAINER"

  sleep 20

  for host in "${HOSTS[@]:1}"; do
    WORKER_DIR="$BEAM_BASE_DIR/$host"
    mkdir -p "$WORKER_DIR"
    WORKER_CONFIG="$WORKER_DIR/cluster-worker.conf"
    cat >"$WORKER_CONFIG" <<EOF
include required(file("$BEAM_CONFIG"))

beam.cluster.expectedWorkerNodes = $EXPECTED_WORKER_NODES
beam.outputs.baseOutputDirectory = "/root/data/output"
EOF
    if [[ "$BEAM_IMAGE_MODE" == "baked" ]]; then
      WORKER_CONFIG_IN_CONTAINER="/root/data/cluster-worker.conf"
    else
      WORKER_CONFIG_IN_CONTAINER="$WORKER_CONFIG"
    fi
    WORKER_ARGS="['--config', '$WORKER_CONFIG_IN_CONTAINER', '--cluster-type', 'worker', '--node-host', '$host', '--node-port', '$AKKA_PORT', '--seed-address', '$SEED_ADDRESS']"
    launch_role "$host" "worker" "$WORKER_DIR" "$WORKER_ARGS" "$WORKER_CONFIG_IN_CONTAINER"
  done

  STATUS=0
  for pid in "${PIDS[@]}"; do
    if ! wait "$pid"; then
      STATUS=1
    fi
  done

  echo "Removing a link to the job's log file."
  echo "The original job log file is in '$JOB_LOG_FILE_PATH'"
  rm "$LINK_TO_JOB_LOG_FILE"

  exit "$STATUS"
fi
