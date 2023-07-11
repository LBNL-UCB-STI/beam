#!/bin/bash
# change storage class for all the entries from archive.csv
# downsides: 1. store/retrieve requests for 1000 objects is pretty expensive for DEEP_ARCHIVE class
# 2. each GLACIER object takes extra space for metadata
# archive.csv should contain entries like this (a single column without header)
: << 'END_COMMENT'
analysis/austin/CTPP/google_output/austin_9_3am.txt_result.txt
analysis/austin/CTPP/google_output/log-20200419T163210.log
output/newyork/new-york-august2021-0-of-10__2023-01-12_18-51-43_rxk/
END_COMMENT
# Entries could be copy-pasted from this spreadsheet https://docs.google.com/spreadsheets/d/1NOYLkXKrhrvioE8KcLU3K-A0nOZiNfZ2mm3WIw5EvE0/edit#gid=1416923711

bucket="beam-outputs"
while read entry; do
    entry=$(echo "$entry" | xargs)
    echo "archiving: s3://$bucket/$entry"
    if [[ ${#entry} -lt 1 ]];   # if the entry is an empty line
    then
      echo "error: $entry"
    elif [[ "$entry" == */ ]];  # if the entry ends with / (slash)
    then
      aws s3 cp --recursive "s3://$bucket/$entry" "s3://$bucket/$entry" --storage-class DEEP_ARCHIVE
    else
      aws s3 cp "s3://$bucket/$entry" "s3://$bucket/$entry" --storage-class DEEP_ARCHIVE
    fi
done < ./archive.csv
echo "done!"
