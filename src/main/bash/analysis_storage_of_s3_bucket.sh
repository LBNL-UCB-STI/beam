#!/bin/bash
# Please use command below to start the shell script
#   bash analysis_s3_bucket.sh beam beam-outputs/output/ > result.txt
# The first parameter beam is the profile of aws configured to beam aws resource,
#   for example: aws s3 ls --profile=beam, then please pass beam as the first parameter to the shell
# The second parameter path is the profile of aws configured to beam aws resource,
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
# The shell script can be executed on local machine with certain prerequisites :
# 1. awscli installed
# 2. `aws s3 ls --profile=beam` can be executed
#

#https://askubuntu.com/questions/1109564/alias-not-working-inside-bash-shell-script
shopt -s expand_aliases

# default profile is beam
beam_profile=${1:-beam}

# default folder name is beam-outputs/, if it is not end with / then append /
folder_name=${2:-'beam-outputs/'}
if [ ${folder_name: -1} != "/" ]; then
    folder_name=$folder_name'/'
fi
echo $folder_name

alias aws_beam='aws --profile=$beam_profile'
source /etc/bash.bashrc

directory_depth=3

# function to calculate folder size, it would be very slow when the folder is deep and large, for example s3://beam-outputs/output/
# aws_beam s3 ls  --summarize --human-readable --recursive  s3://beam-outputs/2018-01/
calculate_folder_size() {
    local result=`aws_beam s3 ls  --summarize --human-readable --recursive  s3://$1 | tail -1`
    IFS=':' read -r -a array <<< "$result"
    echo "${array[1]}"
}

# list all files and folders under the first parameter path
list_all_objects() {
    local path=$1
    local depth=$2

    # create tab string based on depth to print tree structure
    local tab_str=''
    for i in $(seq $depth); do
        tab_str+='    '
    done

    IFS=$'\n'; local object_array=( $(aws_beam s3 ls s3://$path --human-readable) )
    for i in ${!object_array[@]}; do
        # splitting output to each line, for example as below
        # 2019-01-04 19:19:15    1.9 GiB storage-flow-capacity-runs__2019-01-04_18-31-36_f6073a24.zip
        local line_str=${object_array[$i]}
        local file_name="${line_str:31}"
        local file_size="${line_str:19:11}"

        if [ ! -z ${file_name} ] && [ $file_name != "/" ]
        then
            if [ ${file_name: -1} == "/" ]
            then
                # since beam-outputs/output/ is too large, so go deeper
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
                # echo the file name with its size
                echo $tab_str$file_name $file_size
            fi
        fi
    done
}

# starting to generate the directory tree
list_all_objects $folder_name 1

echo shell script finished at `date`
