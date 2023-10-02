#!/bin/bash
# deletes all the entries from approved_to_delete.csv
# approved_to_delete.csv should contain entries like this (a single column without header)
: << 'END_COMMENT'
analysis/austin/CTPP/google_output/austin_9_3am.txt_result.txt
analysis/austin/CTPP/google_output/log-20200419T163210.log
output/newyork/new-york-august2021-0-of-10__2023-01-12_18-51-43_rxk/
END_COMMENT
# Entries could be copy-pasted from this spreadsheet https://docs.google.com/spreadsheets/d/1NOYLkXKrhrvioE8KcLU3K-A0nOZiNfZ2mm3WIw5EvE0/edit#gid=1416923711

while read entry; do
    entry=$(echo "$entry" | xargs)
    echo "trying: s3://beam-outputs/$entry"
    if [[ ${#entry} -lt 1 ]];   # if the entry is an empty line
    then
      echo "error: $entry"
    elif [[ "$entry" == */ ]];  # if the entry ends with / (slash)
    then
      echo aws s3 rm --recursive "s3://beam-outputs/$entry"
    else
      echo aws s3 rm "s3://beam-outputs/$entry"
    fi
done < ./approved_to_delete.csv
echo "done!"
