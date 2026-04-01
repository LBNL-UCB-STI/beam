#!/bin/bash
set -exo pipefail

USER_NAME="${USER:-$(id -un)}"
JOB_ID_FOR_PATH="${SLURM_JOB_ID:-manual}"
AKKA_PORT="${AKKA_PORT:-25520}"
BEAM_IMAGE_MODE="${BEAM_IMAGE_MODE:-baked}"
BEAM_CONFIG="${BEAM_CONFIG:-test/input/beamville/beam.conf}"
DOCKER_IMAGE_NAME="${DOCKER_IMAGE_NAME:-docker://beammodel/beam-environment:jdk-11-4.01}"
IMAGE_TAG="${IMAGE_TAG:-jdk-11-4.01}"
PREBUILT_SIF_PATH="${PREBUILT_SIF_PATH:-}"
LAUNCHER_SMOKE_ONLY="${LAUNCHER_SMOKE_ONLY:-false}"

RUN_BASE_DIR="${RUN_BASE_DIR:-/global/scratch/users/${USER_NAME}/out_beam_direct_${JOB_ID_FOR_PATH}}"
mkdir -p "$RUN_BASE_DIR/logs"

LOG_PATH="$RUN_BASE_DIR/cluster-log-file.log"
exec >>"$LOG_PATH" 2>&1

echo "Direct cluster batch started at $(date '+%Y-%m-%d-%H:%M:%S')"
echo "Job id: ${SLURM_JOB_ID:-unset}"
echo "Run base dir: $RUN_BASE_DIR"
echo "Image mode: $BEAM_IMAGE_MODE"
echo "Config: $BEAM_CONFIG"
echo "Launcher smoke only: $LAUNCHER_SMOKE_ONLY"

touch "$RUN_BASE_DIR/batch-entered.txt"

IMAGE_NAME="beam-environment"
if [[ -n "$PREBUILT_SIF_PATH" ]]; then
  SINGULARITY_IMAGE_PATH="$PREBUILT_SIF_PATH"
else
  SINGULARITY_IMAGE_PATH="$RUN_BASE_DIR/${IMAGE_NAME}_${IMAGE_TAG}.sif"
  echo "Pulling docker image '$DOCKER_IMAGE_NAME' to '$SINGULARITY_IMAGE_PATH'"
  singularity pull --force "$SINGULARITY_IMAGE_PATH" "$DOCKER_IMAGE_NAME"
fi

if [[ ! -f "$SINGULARITY_IMAGE_PATH" ]]; then
  echo "ERROR: SIF not found at '$SINGULARITY_IMAGE_PATH'"
  exit 1
fi

mapfile -t HOSTS < <(scontrol show hostnames "$SLURM_JOB_NODELIST")
printf '%s\n' "${HOSTS[@]}" >"$RUN_BASE_DIR/allocated-hosts.txt"

if [[ "${#HOSTS[@]}" -lt 2 ]]; then
  echo "ERROR: Expected at least 2 hosts, found ${#HOSTS[@]}"
  exit 1
fi

BASE_CONFIG_FOR_INCLUDE="$BEAM_CONFIG"
if [[ "$BEAM_IMAGE_MODE" == "baked" && "$BEAM_CONFIG" != /* ]]; then
  BASE_CONFIG_FOR_INCLUDE="/app/$BEAM_CONFIG"
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
      --output="$RUN_BASE_DIR/logs/${role}-%N.log" \
      bash -lc "
        set -euo pipefail
        singularity exec --writable-tmpfs -B \"$role_dir:/root/data\" \"$SINGULARITY_IMAGE_PATH\" /app/entrypoint.sh $app_args
      " &
  else
    srun --nodes=1 --ntasks=1 --exclusive -w "$host" \
      --output="$RUN_BASE_DIR/logs/${role}-%N.log" \
      bash -lc "
        set -euo pipefail
        export BEAM_DIR='$role_dir'
        export BEAM_APP_ARGS=\"$app_args\"
        singularity run -B \"$role_dir:/root/sources\" \"$SINGULARITY_IMAGE_PATH\"
      " &
  fi
  PIDS+=($!)
}

if [[ "$LAUNCHER_SMOKE_ONLY" == "true" ]]; then
  echo "Running direct launcher smoke only"
  srun --nodes=1 --ntasks=1 --exclusive -w "$MASTER_HOST" \
    --output="$RUN_BASE_DIR/logs/master-%N.log" \
    bash -lc "set -euo pipefail; echo master-host=\$(hostname); touch '$RUN_BASE_DIR/master-smoke.txt'; sleep 5"
  for host in "${HOSTS[@]:1}"; do
    srun --nodes=1 --ntasks=1 --exclusive -w "$host" \
      --output="$RUN_BASE_DIR/logs/worker-%N.log" \
      bash -lc "set -euo pipefail; HOSTNAME_VALUE=\$(hostname); echo worker-host=\$HOSTNAME_VALUE; touch '$RUN_BASE_DIR'/worker-\$HOSTNAME_VALUE.txt; sleep 5"
  done
  echo "Direct launcher smoke completed at $(date '+%Y-%m-%d-%H:%M:%S')"
  exit 0
fi

MASTER_DIR="$RUN_BASE_DIR/master"
mkdir -p "$MASTER_DIR"
MASTER_CONFIG="$MASTER_DIR/cluster-master.conf"
cat >"$MASTER_CONFIG" <<EOF
include required(file("$BASE_CONFIG_FOR_INCLUDE"))

beam.cluster.expectedWorkerNodes = $EXPECTED_WORKER_NODES
beam.outputs.baseOutputDirectory = "/root/data/output"
EOF

if [[ "$BEAM_IMAGE_MODE" == "baked" ]]; then
  MASTER_CONFIG_IN_CONTAINER="/root/data/cluster-master.conf"
else
  MASTER_CONFIG_IN_CONTAINER="$MASTER_CONFIG"
fi

printf -v MASTER_ARGS "%q " \
  --config "$MASTER_CONFIG_IN_CONTAINER" \
  --cluster-type master \
  --node-host "$MASTER_HOST" \
  --node-port "$AKKA_PORT" \
  --seed-address "$SEED_ADDRESS" \
  --use-local-worker false
launch_role "$MASTER_HOST" "master" "$MASTER_DIR" "$MASTER_ARGS" "$MASTER_CONFIG_IN_CONTAINER"

sleep 20

for host in "${HOSTS[@]:1}"; do
  WORKER_DIR="$RUN_BASE_DIR/$host"
  mkdir -p "$WORKER_DIR"
  WORKER_CONFIG="$WORKER_DIR/cluster-worker.conf"
  cat >"$WORKER_CONFIG" <<EOF
include required(file("$BASE_CONFIG_FOR_INCLUDE"))

beam.cluster.expectedWorkerNodes = $EXPECTED_WORKER_NODES
beam.outputs.baseOutputDirectory = "/root/data/output"
EOF

  if [[ "$BEAM_IMAGE_MODE" == "baked" ]]; then
    WORKER_CONFIG_IN_CONTAINER="/root/data/cluster-worker.conf"
  else
    WORKER_CONFIG_IN_CONTAINER="$WORKER_CONFIG"
  fi

  printf -v WORKER_ARGS "%q " \
    --config "$WORKER_CONFIG_IN_CONTAINER" \
    --cluster-type worker \
    --node-host "$host" \
    --node-port "$AKKA_PORT" \
    --seed-address "$SEED_ADDRESS"
  launch_role "$host" "worker" "$WORKER_DIR" "$WORKER_ARGS" "$WORKER_CONFIG_IN_CONTAINER"
done

STATUS=0
for pid in "${PIDS[@]}"; do
  if ! wait "$pid"; then
    STATUS=1
  fi
done

echo "Direct cluster batch completed with status $STATUS at $(date '+%Y-%m-%d-%H:%M:%S')"
exit "$STATUS"
