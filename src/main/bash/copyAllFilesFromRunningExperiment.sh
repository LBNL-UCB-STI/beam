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
    source_exp_dir="$to_copy"/"$exp_id"
    target_exp_dir="$source_exp_dir"_"$dir_date"
    source_suggestions_dir="$source_exp_dir"/suggestions

    sudo mkdir $to_copy
    sudo mkdir $source_exp_dir
    sudo mkdir $source_suggestions_dir

    echo "source_exp_dir: $source_exp_dir";
    echo "target_exp_dir: $target_exp_dir";
    echo "suggestions dir: $source_suggestions_dir";

    for d in */ ; do
        current=`date +%s`
        last_modified=`stat -c "%Y" ${d::-1}/beam-log.out`

        if [ $(($current-$last_modified)) -gt 180 ]; then
            echo "moving dir ${d::-1}"
            #echo "mv ${d::-1} $source_dir/"
            echo mv "${d::-1}" "$1/to_copy/$exp_id/suggestions/${d::-1}"
            sudo mv "${d::-1}" "$1/to_copy/$exp_id/suggestions/${d::-1}"
        else
            echo "${d::-1} in progress cur_date: $current - last_modified: $last_modified";
        fi
    done


    echo $(pwd)
    echo "Exiting for test purposes.."
    exit 0

    if [ -z "$(ls -A $source_exp_dir)" ]; then
       echo "Empty source directory $source_dir"
       sudo mv "$source_exp_dir" "$target_exp_dir"
       exit 0
    else
        echo "Copying the files... from $source_dir"
        #sudo scp -i ~/.ssh/result_host_cert.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -r $source_dir ubuntu@$2:~/sigoptResults/
        echo "Copying completed..."
        sudo mv "$source_exp_dir" "$target_exp_dir"
        echo "Moved the suggestions from $source_exp_dir to $target_exp_dir"
        echo "Done.."
    fi
fi

