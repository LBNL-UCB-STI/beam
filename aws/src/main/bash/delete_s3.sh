#!/bin/bash
# deletes all the entries from approved_to_delete.csv

while read in; do
    in=$(echo "$in" | xargs)
    echo "trying: s3://beam-outputs/$in"
    if [[ ${#in} -lt 1 ]];
    then
      echo "error: $in"
    elif [[ "$in" == */ ]];
    then
      aws s3 rm --recursive "s3://beam-outputs/$in"
    else
      aws s3 rm "s3://beam-outputs/$in"
    fi
done < ./approved_to_delete.csv
echo "done!"
