#!/usr/bin/env bash

export src_it=$1

export run_name="$(basename "$(dirname "$(dirname "$src_it")")")"
export run_path="prepare-warmstart/${run_name}"

echo "$run_path"

mkdir -p ${run_path}/ITERS/it.0

cp ${src_it}/*.linkstats.csv.gz ${run_path}/ITERS/it.0/0.linkstats.csv.gz
cp ${src_it}/*.plans.xml.gz ${run_path}/output_plans.xml.gz
cp ${src_it}/*.skims.csv.gz ${run_path}/ITERS/it.0/0.skims.csv.gz

cd prepare-warmstart
zip -r "${run_name}_warmstart.zip" "$run_name"
aws --region "us-east-2" s3 cp *.zip s3://beam-outputs/
echo "S3 URL: https://s3.us-east-2.amazonaws.com/beam-outputs/${run_name}_warmstart.zip"

cd ..
rm -rf prepare-warmstart
