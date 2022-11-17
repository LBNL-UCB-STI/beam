#!/bin/bash
# Please use command below to start the shell script
#   bash analysis_s3_bucket.sh beam > result.txt
# The first parameter beam is the profile of aws configured to beam aws rescource,
#   for example: aws s3 ls --profile=beam, then please pass beam as the first parameter to the shell
# When set directory_depth to 3, the result would be:
#beam_outputs/
#    directoryA/ 10 GiB
#        directoryA-1/   8 Gib
#        FileA   1 Gib
#        FileB   145 Mib
#    directoryB/ 110 GiB
#        FileC   198 Mib
# If set directory_depth to 4, the depth will go one level deeper
#

shopt -s expand_aliases #https://askubuntu.com/questions/1109564/alias-not-working-inside-bash-shell-script

beam_profile=${1:-beam}

alias aws_beam='aws --profile=$beam_profile'
source /etc/bash.bashrc

directory_depth=3

# aws_beam s3 ls  --summarize --human-readable --recursive  s3://beam-outputs/2018-01/

# aws_beam s3 ls s3://beam-outputs/ --human-readable

calculate_folder_size() {
    #sleep 20
    #echo calculate_folder_size $1
    local result=`aws_beam s3 ls  --summarize --human-readable --recursive  s3://$1 | tail -1`
    IFS=':' read -r -a array <<< "$result"
    # size=`awk -vRS=':'  '{print "----------";print}' <<< $result`
    echo "${array[1]}"
}

list_all_objects() {
    local path=$1
    local depth=$2
    # echo ''
    # echo ''
    # aws_beam s3 ls  --summarize --human-readable --recursive  s3://beam-outputs/output/austin/austin-nov2021-bike-draft__2021-11-20_02-53-53_scg/
    # if [[ $path =~ "beam-outputs/output/" ]] && (($depth == $((directory_depth+2)))); then
    #     echo '***return 0 with path' $path -- `date`
    #     return 0
    # elif (($depth >= $directory_depth)); then
    #     echo '***return 0 with path' $path -- `date`
    #     return 0
    # fi

    local tab_str=''
    for i in $(seq $depth); do
        tab_str+='    '
    done

    #echo list_all_objects $path
    #echo list_all_objects called, path $path, depth $depth
    IFS=$'\n'; local var1=( $(aws_beam s3 ls s3://$path --human-readable) )

    for i in ${!var1[@]}; do
        # 2019-01-04 19:19:15    1.9 GiB storage-flow-capacity-runs__2019-01-04_18-31-36_f6073a24.zip
        local line_str=${var1[$i]}
        local file_name="${line_str:31}"
        local file_size="${line_str:19:11}"

        if [ ! -z ${file_name} ] && [ $file_name != "/" ]
        then
            if [ ${file_name: -1} == "/" ]
            then
                # echo '###'calculate_folder_size $path$file_name -- `date`
                # local folder_size=`calculate_folder_size $path$file_name`
                # echo $tab_str$file_name $folder_size
                # if [ $((depth+1)) -le $directory_depth ]
                # then
                #     # recursive function call
                #     list_all_objects $path$file_name $((depth+1))
                # fi

                if [[ $path$file_name =~ "beam-outputs/output/" ]] && (($depth == $((directory_depth+2)))); then
                    #echo '###'calculate_folder_size $path$file_name -- `date`
                    local folder_size=`calculate_folder_size $path$file_name`
                    echo $tab_str$file_name $folder_size
                elif (($depth >= $directory_depth)); then
                    #echo '###'calculate_folder_size $path$file_name -- `date`
                    local folder_size=`calculate_folder_size $path$file_name`
                    echo $tab_str$file_name $folder_size
                else
                    echo $tab_str$file_name
                    # recursive function call
                    list_all_objects $path$file_name $((depth+1))
                fi
            else
                # echo '### just file:' $line_str
                echo $tab_str$file_name $file_size
            fi
        fi
    done
}

folder_name='beam-outputs/'
echo $folder_name

list_all_objects $folder_name 1

echo 'shell script finished'
