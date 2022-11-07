#!/bin/bash

export DOCKER_IMAGE="dimaopen/beam_git:1.0.10"

while [ $# -gt 0 ]; do
  case "$1" in
    --branch=*)
      beam_branch_name="${1#*=}"
      ;;
    --data_branch=*)
      beam_data_branch_name="${1#*=}"
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

export BEAM_BRANCH_NAME=$beam_branch_name \
export BEAM_DATA_BRANCH_NAME=$beam_data_branch_name \
export BEAM_COMMIT_SHA=$beam_revision \
export BEAM_CONFIG=$beam_config \
export MAXRAM=$max_ram \
export GOOGLE_API_KEY=${google_api_key:-not_set} \
export AWS_SECRET_ACCESS_KEY=${aws_secret_access_key:-not_set} \
export AWS_ACCESS_KEY_ID=${aws_access_key_id:-not_set} \
export S3_PUBLISH=${s3_publish:-true} \
export S3_REGION=$region \

echo "Starting beam"
printf "beam branch is %s\n" "$beam_branch_name"
printf "commit is %s\n" "$beam_revision"
printf "beam config is %s\n" "$beam_config"

export MOUNTED_DIR="$SCRATCH/beam_runs/beam_$(date +%Y%m%d%H%M%S)"
mkdir -p $MOUNTED_DIR || { echo "Cannot create dir $MOUNTED_DIR" ; exit 1; }

echo "Created dir: $MOUNTED_DIR"

shifterimg pull $DOCKER_IMAGE  || { echo "Cannot download image $DOCKER_IMAGE" ; exit 1; }

sbatch shifter_job.sh