#!/bin/bash
echo "Generating experiments for config: $1."
java -cp build/libs/beam.jar beam.experiment.ExperimentGenerator --experiments $1

echo "Experiments generated in: $(dirname $1)."
base_dir=$(dirname $1)

echo "Running experiment batch on base $base_dir."
$base_dir/runs/batchRunExperiment.sh $2
