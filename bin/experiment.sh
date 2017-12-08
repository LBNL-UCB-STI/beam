#!/bin/bash


echo "Generating experiments for config: $1"
java -cp build/libs/beam.jar beam.experiment.ExperimentGenerator --experiments $1

echo "Experiments generated in: $(dirname $1)"
IFS=$'\n' read -d '' -r -a experiments < $(dirname $1)/experiments.csv

#for exp in "${experiments[@]}"
for (( i=1; i<${#experiments[@]}; i++ ));
do
    IFS=', ' read -r -a csv_rows <<< "${experiments[$i]}"
    echo "Running experiment using config ${csv_rows[4]}/beam.conf"
    if [ "$2" == "cloud" ]; then
        ${csv_rows[4]}/runExperiment.sh cloud  > ${csv_rows[4]}/console.log 2>&1
    else
        ${csv_rows[4]}/runExperiment.sh  > ${csv_rows[4]}/console.log 2>&1
    fi
done
unset experiments

