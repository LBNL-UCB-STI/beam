#!/usr/bin/env bash

export s3_src=$1
export src_name=$(basename $s3_src)

mkdir prepare-warmstart
cd prepare-warmstart
wget $s3_src
unzip $src_name
rm -rf $src_name

cd `find ./ -name ITERS`
mv `find -maxdepth 1 -name 'it.*' | sort -V  | tail -n1` it_ws
rm -rf it.*
mkdir it.0
mv it_ws/*.linkstats.csv.gz it.0/0.linkstats.csv.gz
mv ../output_plans.xml.gz ../output_plans.xml.gz.back
rm -rf it_ws ../*.csv ../*.png ../*.gz ../*.txt
mv ../output_plans.xml.gz.back ../output_plans.xml.gz
cd ..

t_name=$(basename `pwd`)

cd ..
zip -r "${t_name}_warmstart.zip" "$t_name"
aws --region "us-east-2" s3 cp *.zip s3://beam-outputs/
echo "S3 URL: https://s3.us-east-2.amazonaws.com/beam-outputs/${t_name}_warmstart.zip"
