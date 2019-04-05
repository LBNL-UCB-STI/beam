#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Illegal number of parameters";
    exit 1;
fi

filename=$1
path=$(find output -maxdepth 2 -type d -name $1 -print -quit)

zip -r "$filename.zip" . -x "output/*"
zip -ur "$filename.zip" $path/ITERS/it.$2

aws --region "us-east-2" s3 cp "$filename.zip" s3://beam-outputs/
echo "S3 URL: https://s3.us-east-2.amazonaws.com/beam-outputs/$filename.zip";
