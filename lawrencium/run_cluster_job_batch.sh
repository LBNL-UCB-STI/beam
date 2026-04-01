#!/bin/bash
set -exo pipefail

SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/run_cluster_job.sh"

touch "$BEAM_BASE_DIR/batch-wrapper-entered.txt"
exec >>"$JOB_LOG_FILE_PATH" 2>&1

echo "Entered batch helper at $(date "+%Y-%m-%d-%H:%M:%S")"
echo "Helper cwd: $(pwd)"
echo "Launcher path: $SCRIPT_PATH"

export RUN_CLUSTER_JOB_MODE=batch
exec bash "$SCRIPT_PATH"
