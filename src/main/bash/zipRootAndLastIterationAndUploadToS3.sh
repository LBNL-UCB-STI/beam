#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Illegal number of parameters";
    exit 1;
fi

zip -r beam.zip . -x "output/*"
zip -ur beam.zip $1/ITERS/it.$2

aws --region "us-east-2" s3 cp beam.zip s3://beam-outputs/
