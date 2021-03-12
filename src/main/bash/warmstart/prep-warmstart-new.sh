#!/usr/bin/env bash

# Run it like `prep-warmstart-new.sh #PATH_TO_RUN_OUTPUT# #ITERATION_NUMBER#`
# For example, `prep-warmstart-new.sh /home/ubuntu/git/beam/output/sfbay/sfbay-smart-base__2019-07-24_06-11-31 50`
export run_folder=$1
export it_number=$2

output_folder="${run_folder}_${it_number}iter"
warmstart_file="${output_folder}_warmstart.zip"
s3_dest="s3://beam-outputs/output/sfbay/${output_folder}"

# Create the structure of folders
mkdir -p "${output_folder}/ITERS/it.${it_number}"
echo "Created ${output_folder}"

# Copy first level files to the output folder
find "${run_folder}" -maxdepth 1 -type f -exec cp {} "${output_folder}" \;
echo "Copied first level files from ${run_folder} to ${output_folder}"
ls -la "${output_folder}"

# Copy needed files from iteration folder to the output folder
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.plans.xml.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.plans.csv.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.rideHailFleet.csv.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.skimsTravelTimeObservedVsSimulated_Aggregated.csv.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.skimsOD_Aggregated.csv.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.skimsTAZ_Aggregated.csv.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.skimsTransitCrowding_Aggregated.csv.gz" "${output_folder}/ITERS/it.${it_number}"
cp "${run_folder}/ITERS/it.${it_number}/${it_number}.linkstats.csv.gz" "${output_folder}/ITERS/it.${it_number}"

echo "Copied needed files from ${run_folder}/ITERS/it.${it_number} to ${output_folder}/ITERS/it.${it_number}"

zip -r "${warmstart_file}" "${output_folder}"
echo "Created zip ${warmstart_file}"
ls -la "${warmstart_file}"

cp "${warmstart_file}" "${output_folder}"
aws --region "us-east-2" s3 cp "${output_folder}" "${s3_dest}" --recursive

echo "Copied to S3 to ${s3_dest}"
