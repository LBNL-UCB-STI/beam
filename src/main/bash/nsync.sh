#!/bin/bash

rsync --ignore-existing -avzhe ssh csheppar@cori.nersc.gov:/global/cscratch1/sd/csheppar/$1 /Users/critter/Documents/beam/beam-output/
scp csheppar@cori.nersc.gov:/global/cscratch1/sd/csheppar/$1/calibration.csv /Users/critter/Documents/beam/beam-output/$1

#calibration_2017-06-27_22-57-55
#calibration_2017-06-27_23-02-10
#calibration_2017-06-27_23-02-14
#calibration_2017-06-27_23-02-17
#calibration_2017-06-27_23-02-19
#calibration_2017-06-27_23-02-20
#calibration_2017-06-27_23-02-22
