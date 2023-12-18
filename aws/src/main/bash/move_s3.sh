#!/bin/bash
# get all objects for each entry from archive_test.csv, tar them
# and put to glacier-beam-outputs bucket with storage class DEEP_ARCHIVE
# archive_test.csv should contain entries like this (a single column without header)
: << 'END_COMMENT'
output/newyork/new-york-august2021-0-of-10__2023-01-12_18-51-43_rxk/
output/sfbay/oakland-rh-010__2023-02-12_10-34-24_ecx/
END_COMMENT
# Entries could be copy-pasted from this spreadsheet https://docs.google.com/spreadsheets/d/1NOYLkXKrhrvioE8KcLU3K-A0nOZiNfZ2mm3WIw5EvE0/edit#gid=1416923711

bucket="beam-outputs"
arch_bucket="glacier-beam-outputs"
while read entry; do
    entry=$(echo "$entry" | xargs)
    echo "archiving: s3://$bucket/$entry"
    if [[ ${#entry} -lt 1 ]];   # if the entry is an empty line
    then
      echo "error: $entry"
    elif [[ "$entry" == */ ]];  # if the entry ends with / (slash)
    then
      dir_name=$(basename "$entry")
      aws s3 cp --quiet --recursive "s3://$bucket/$entry" "$dir_name" || exit 1
      if [ -n "$(ls -A "$dir_name" 2>/dev/null)" ]  # if the directory contains anything
      then
        new_path=$(echo "$entry" | rev | cut -c 2- | rev)
        size=$(du -sb "$dir_name" | awk '{print $1;}')
        exp_size=$((size + 100000))
        tar -cf - "$dir_name" | aws s3 cp - "s3://$arch_bucket/$new_path.tar" --storage-class DEEP_ARCHIVE --no-progress --expected-size $exp_size || exit 1
        aws s3 rm --quiet --recursive "s3://$bucket/$entry" || exit 1
        rm -r "$dir_name" || exit 1
      else
        echo "empty $dir_name"
        rm -r "$dir_name"
      fi
    else
      aws s3 mv "s3://$bucket/$entry" "s3://$arch_bucket/$entry" --storage-class DEEP_ARCHIVE
    fi
done < ./archive_test.csv
echo "done!"
