sudo chmod 600 ~/.ssh/result_host_cert.pem


if [ -z "$(ls -A $1)" ]; then
   echo "Empty"
   exit 0
else
   echo "Not Empty"
   echo "$(du -sh $1)"


    # 1. loop through all the suggestions folders
    # 2. and if the beam-log.out file update is more than 60 seconds then we need to move the suggestion to the one level up in a tobecopied folder
    # 3. and then run scp command from that folder

    cd $1
    machine_name="$(ec2metadata --instance-id)"
    echo "Machine name: $machine_name"

    exp_id="${PWD##*/}"
    cd suggestions

    dir_date=`date +%s`
    to_copy="../to_copy"

    sudo mkdir $to_copy
    sudo mkdir $to_copy/$exp_id

    source_dir=$to_copy/$exp_id
    target_dir="$source_dir"_"$dir_date"

    #echo "source_dir: $source_dir";

    #echo "$target_dir";

    for d in */ ; do
        current=`date +%s`
        last_modified=`stat -c "%Y" ${d::-1}/beam-log.out`

        if [ $(($current-$last_modified)) -gt 180 ]; then
            echo "moving dir $d"
            sudo mv -r "${d::-1}" "$source_dir/"
        else
            echo "${d::-1} in progress cur_date: $current - last_modified: $last_modified";
        fi
    done


    if [ -z "$(ls -A $source_dir)" ]; then
       echo "Empty source directory $source_dir"
       sudo rm -rf ../to_copy
       exit 0
    else
        echo "Copying the files..."
        sudo scp -i ~/.ssh/result_host_cert.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -r $source_dir ubuntu@$2:~/sigoptResults/
        echo "Copying completed..."
        sudo mv "$source_dir" "$target_dir"
        echo "Moved the suggestions"
        echo "Done.."
    fi
fi

