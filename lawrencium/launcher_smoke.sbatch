#!/bin/bash
set -exo pipefail

USER_NAME="${USER:-$(id -un)}"
JOB_ID_FOR_PATH="${SLURM_JOB_ID:-manual}"
RUN_BASE_DIR="${RUN_BASE_DIR:-/global/scratch/users/${USER_NAME}/launcher_smoke_${JOB_ID_FOR_PATH}}"
PREBUILT_SIF_PATH="${PREBUILT_SIF_PATH:-}"

echo "launcher_smoke entered"
echo "launcher_smoke job id: ${SLURM_JOB_ID:-unset}"
echo "launcher_smoke run dir: ${RUN_BASE_DIR}"

mkdir -p "$RUN_BASE_DIR/logs"

LOG_PATH="$RUN_BASE_DIR/cluster-log-file.log"
exec >>"$LOG_PATH" 2>&1

set -u

echo "Launcher smoke started at $(date '+%Y-%m-%d-%H:%M:%S')"
echo "Job id: ${SLURM_JOB_ID:-unset}"
echo "Job node list: ${SLURM_JOB_NODELIST:-unset}"
echo "Submit dir: ${SLURM_SUBMIT_DIR:-unset}"
echo "Run base dir: $RUN_BASE_DIR"
echo "Prebuilt SIF: ${PREBUILT_SIF_PATH:-<unset>}"

touch "$RUN_BASE_DIR/batch-entered.txt"
env | sort >"$RUN_BASE_DIR/batch-env.txt"

mapfile -t HOSTS < <(scontrol show hostnames "$SLURM_JOB_NODELIST")
printf '%s\n' "${HOSTS[@]}" >"$RUN_BASE_DIR/allocated-hosts.txt"

echo "Hosts: ${HOSTS[*]}"

MASTER_HOST="${HOSTS[0]}"
echo "Master host: $MASTER_HOST"

srun --nodes=1 --ntasks=1 --exclusive -w "$MASTER_HOST" \
  --output="$RUN_BASE_DIR/logs/master-%N.log" \
  bash -lc "set -euo pipefail; echo master-host=\$(hostname); touch '$RUN_BASE_DIR/master-smoke.txt'; sleep 5"

for host in "${HOSTS[@]:1}"; do
  srun --nodes=1 --ntasks=1 --exclusive -w "$host" \
    --output="$RUN_BASE_DIR/logs/worker-%N.log" \
    bash -lc "set -euo pipefail; echo worker-host=\$(hostname); touch '$RUN_BASE_DIR/worker-\$(hostname).txt'; sleep 5"
done

if [[ -n "$PREBUILT_SIF_PATH" ]]; then
  if [[ ! -f "$PREBUILT_SIF_PATH" ]]; then
    echo "ERROR: PREBUILT_SIF_PATH does not exist: $PREBUILT_SIF_PATH"
    exit 1
  fi

  echo "Testing singularity exec on allocated nodes"

  srun --nodes=1 --ntasks=1 --exclusive -w "$MASTER_HOST" \
    --output="$RUN_BASE_DIR/logs/master-container-%N.log" \
    bash -lc "set -euo pipefail; singularity exec '$PREBUILT_SIF_PATH' /bin/hostname"

  for host in "${HOSTS[@]:1}"; do
    srun --nodes=1 --ntasks=1 --exclusive -w "$host" \
      --output="$RUN_BASE_DIR/logs/worker-container-%N.log" \
      bash -lc "set -euo pipefail; singularity exec '$PREBUILT_SIF_PATH' /bin/hostname"
  done
fi

echo "Launcher smoke completed at $(date '+%Y-%m-%d-%H:%M:%S')"
