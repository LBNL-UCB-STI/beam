#!/bin/bash
# get all objects for each entry from archive_test.csv, tar them
# and put to glacier-beam-outputs bucket with storage class DEEP_ARCHIVE

bucket="beam-outputs"
arch_bucket="glacier-beam-outputs"
while read in; do
    in=$(echo "$in" | xargs)
    echo "archiving: s3://$bucket/$in"
    if [[ ${#in} -lt 1 ]];
    then
      echo "error: $in"
    elif [[ "$in" == */ ]];
    then
      dir_name=$(basename "$in")
      aws s3 cp --quiet --recursive "s3://$bucket/$in" "$dir_name" || exit 1
      if [ -n "$(ls -A "$dir_name" 2>/dev/null)" ]
      then
        new_path=$(echo "$in" | rev | cut -c 2- | rev)
        size=$(du -sb "$dir_name" | awk '{print $1;}')
        exp_size=$((size + 100000))
        tar -cf - "$dir_name" | aws s3 cp - "s3://$arch_bucket/$new_path.tar" --storage-class DEEP_ARCHIVE --no-progress --expected-size $exp_size || exit 1
        rm -r "$dir_name" || exit 1
        aws s3 rm --quiet --recursive "s3://$bucket/$in" || exit 1
      else
        echo "empty $dir_name"
        rm -r "$dir_name"
      fi
    else
      aws s3 mv "s3://$bucket/$in" "s3://$arch_bucket/$in" --storage-class DEEP_ARCHIVE
    fi
done < ./archive_test.csv
echo "done!"
