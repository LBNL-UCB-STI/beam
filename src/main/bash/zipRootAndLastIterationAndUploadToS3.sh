#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Illegal number of parameters";
    exit 1;
fi

filename=$(basename $1)

zip -r "$filename.zip" . -x "output/*"
zip -ur "$filename.zip" $1/ITERS/it.$2

aws --region "us-east-2" s3 cp "$filename.zip" s3://beam-outputs/
