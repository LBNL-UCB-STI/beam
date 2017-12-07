#!/bin/bash
java -cp build/libs/beam.jar beam.experiment.ExperimentGenerator --experiments $1

IFS=$'\n' read -d '' -r -a experiments < $(dirname $1)/experiments.csv

#for exp in "${experiments[@]}"
for (( i=1; i<${#experiments[@]}; i++ ));
do
    IFS=', ' read -r -a csv_rows <<< "${experiments[$i]}"
    eval "${csv_rows[4]}/runExperiment.sh"
done
