#!/bin/bash

#SBATCH --nodes=1-1
#SBATCH --cpus-per-task=32
#SBATCH --qos=regular
#SBATCH --constraint=haswell
#SBATCH --time=48:00:00

srun -n 1 shifter \
 -e BEAM_BRANCH_NAME=$BEAM_BRANCH_NAME \
 -e BEAM_COMMIT_SHA=$BEAM_COMMIT_SHA \
 -e BEAM_CONFIG=$BEAM_CONFIG \
 -e MAXRAM=$MAXRAM \
 -e GOOGLE_API_KEY=$GOOGLE_API_KEY \
 -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY \
 -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID \
 -e S3_PUBLISH=$S3_PUBLISH \
 -e S3_REGION=$S3_REGION \
 --volume="$MOUNTED_DIR:/app/sources" \
 --image=$DOCKER_IMAGE /app/entrypoint.sh