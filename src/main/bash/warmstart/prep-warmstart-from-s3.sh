#!/usr/bin/env bash

# Run it like #PATH_TO_S3_OUTPUT# #ITERATION_NUMBER#
# For example, `prep-warmstart.sh https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/sfbay/gemini-base-2035-warmstart-files__2021-03-10_12-39-40_dsq 50`
export s3output=$1
export it_number=$2

s3region='us-east-2'
s3_dest="s3://beam-outputs/warmstart/sfbay/"

output_folder="${s3output##*/}_it.$it_number"
it_path="ITERS/it.$it_number"
warmstart_file="${output_folder}_warmstart.zip"
s3_root_path="s3://beam-outputs/${s3output##*#}"

# Create the structure of folders
rm -rf $output_folder
mkdir -p "$output_folder/$it_path"
echo "Created ${output_folder/$it_path}"

copy_from_root_to_root(){
        file_name=$1
        aws s3 --region=$s3region cp "$s3_root_path/$file_name" "$output_folder/$file_name"
}

copy_from_it_to_it(){
        file_name=$it_number.$1
        aws s3 --region=$s3region cp "$s3_root_path/$it_path/$file_name" "$output_folder/$it_path/$file_name"
}


copy_from_root_to_root output_personAttributes.xml.gz
copy_from_root_to_root population.csv.gz
copy_from_root_to_root households.csv.gz
copy_from_root_to_root vehicles.csv.gz

copy_from_it_to_it plans.xml.gz
copy_from_it_to_it plans.csv.gz
copy_from_it_to_it rideHailFleet.csv.gz
copy_from_it_to_it skimsTravelTimeObservedVsSimulated_Aggregated.csv.gz
copy_from_it_to_it skimsOD_Aggregated.csv.gz
copy_from_it_to_it skimsTAZ_Aggregated.csv.gz
copy_from_it_to_it skimsTransitCrowding_Aggregated.csv.gz
copy_from_it_to_it linkstats.csv.gz

zip -r "${warmstart_file}" "${output_folder}"
aws --region "us-east-2" s3 cp "$warmstart_file" "${s3_dest}"
