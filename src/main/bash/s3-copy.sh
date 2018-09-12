#!/usr/bin/env bash

export opth=$1
export psuf=$2
export s3p=""
for file in $opth/*; do sudo cp /var/log/cloud-init-output.log "$file" && sudo zip -r "${file%.*}$psuf.zip" "$file"; done;
for file in $opth/*.zip; do s3p="$s3p https://s3.us-east-2.amazonaws.com/beam-outputs/$(basename $file)"; done;
sudo aws --region "us-east-2" s3 cp $opth/*.zip s3://beam-outputs/
echo "S3 URLs: $s3p"
