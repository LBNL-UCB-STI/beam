#!/bin/bash

echo "Executing the job .."

export NOTIFICATION_INSTANCE_ID=$SLURMD_NODENAME
export NOTIFICATION_INSTANCE_TYPE="Lawrencium $SLURM_JOB_PARTITION"
export NOTIFICATION_HOST_NAME=$HOSTNAME

export NOTIFICATION_WEB_BROWSER="TODO"

export PULL_CODE="true"
export PULL_DATA="true"

# required for doing speed comparison (BEAM simulation vs Google observations)
export GOOGLE_API_KEY=""

# instance (node) has no region when we using Lawrencium
export NOTIFICATION_INSTANCE_REGION=""
# there is no shutdown wait when we using Lawrencium
export NOTIFICATION_SHUTDOWN_WAIT=""

FULL_DOCKER_IMAGE_NAME="docker://${DOCKER_IMAGE_NAMESPACE}/${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_TAG}"
SINGULARITY_IMAGE_NAME="${DOCKER_IMAGE_NAME}_${DOCKER_IMAGE_TAG}.sif"

# to use https for pulling data repository despite a url configured for it
export ENFORCE_HTTPS_FOR_DATA_REPOSITORY="true"

echo "Pulling docker image '$FULL_DOCKER_IMAGE_NAME' ..."
singularity pull --force "$FULL_DOCKER_IMAGE_NAME"

echo "Running singularity image '$SINGULARITY_IMAGE_NAME' ..."
# singularity run -B "$BEAM_DIR:/app/sources" "$SINGULARITY_IMAGE_NAME"

singularity run hello-world_latest.sif

echo "Removing a link to the job's log file."
echo "The original job log file is in '$JOB_LOG_FILE_PATH'"
rm "$LINK_TO_JOB_LOG_FILE"

echo "Done."
