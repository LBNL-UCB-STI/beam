#!/bin/bash
set -x
set -e

S3_REGION=us-east-2
RESULT_PATH=$1
RESULT_DIR=$(basename $RESULT_PATH)
OUTPUT_DIR=username/r5vsgh

aws --region "$S3_REGION" s3 cp "$RESULT_PATH" s3://beam-outputs/$OUTPUT_DIR/$RESULT_DIR --recursive
