#!/bin/bash
# change storage class for all the entries from archive.csv
# downsides: 1. store/retrieve requests for 1000 objects is pretty expensive for DEEP_ARCHIVE class
# 2. each GLACIER object takes extra space for metadata

bucket="beam-outputs"
while read in; do
    in=$(echo "$in" | xargs)
    echo "archiving: s3://$bucket/$in"
    if [[ ${#in} -lt 1 ]];
    then
      echo "error: $in"
    elif [[ "$in" == */ ]];
    then
      aws s3 cp --recursive "s3://$bucket/$in" "s3://$bucket/$in" --storage-class DEEP_ARCHIVE
    else
      aws s3 cp "s3://$bucket/$in" "s3://$bucket/$in" --storage-class DEEP_ARCHIVE
    fi
done < ./archive.csv
echo "done!"
