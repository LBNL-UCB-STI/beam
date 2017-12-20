#!/bin/bash

# search through runs directory and run individual runExperiment.sh
for dir in {{ EXPERIMENT_PATH }}/runs/*; do 
    echo $dir
    $dir/runExperiment.sh > $dir/console.log 2>&1
    grep ModeChoice $dir/output/ITERS/it.0/0.events.csv | grep -Eo "ModeChoice,,\w*" | sort | uniq -c
done 
