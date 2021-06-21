#!/bin/bash

DOCKER_IMAGE="dimaopen/beam_git:1.0.8"

while [ $# -gt 0 ]; do
  case "$1" in
    --branch=*)
      beam_branch_name="${1#*=}"
      ;;
    --revision=*)
      beam_revision="${1#*=}"
      ;;
    --config=*)
      beam_config="${1#*=}"
      ;;
    --region=*)
      region="${1#*=}"
      ;;
    --max_ram=*)
      max_ram="${1#*=}"
      ;;
    --google_api_key=*)
      google_api_key="${1#*=}"
      ;;
    --aws_access_key_id=*)
      aws_access_key_id="${1#*=}"
      ;;
    --aws_secret_access_key=*)
      aws_secret_access_key="${1#*=}"
      ;;
    --s3_publish=*)
      s3_publish="${1#*=}"
      ;;
    *)
      printf "Error: Invalid argument: %s\n" "$1"
      exit 1
  esac
  shift
done


echo "Starting beam"
printf "beam branch is %s\n" "$beam_branch_name"
printf "commit is %s\n" "$beam_revision"
printf "beam config is %s\n" "$beam_config"

MOUNTED_DIR="$SCRATCH/beam_runs/beam_$(date +%Y%m%d%H%M%S)"
mkdir -p $MOUNTED_DIR || { echo "Cannot create dir $MOUNTED_DIR" ; exit 1; }

echo "Created dir: $MOUNTED_DIR"


shifterimg pull $DOCKER_IMAGE  || { echo "Cannot download image $DOCKER_IMAGE" ; exit 1; }
shifter -e BEAM_BRANCH_NAME=$beam_branch_name \
 -e BEAM_COMMIT_SHA=$beam_revision \
 -e BEAM_CONFIG=$beam_config \
 -e MAXRAM=$max_ram \
 -e GOOGLE_API_KEY=${google_api_key:-not_set} \
 -e AWS_SECRET_ACCESS_KEY=${aws_secret_access_key:-not_set} \
 -e AWS_ACCESS_KEY_ID=${aws_access_key_id:-not_set} \
 -e S3_PUBLISH=${s3_publish:-true} \
 -e S3_REGION=$region \
 --volume="$MOUNTED_DIR:/app/sources" \
 --image=$DOCKER_IMAGE /app/entrypoint.sh


echo "run_beam exiting..."