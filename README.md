# BEAM

[![Build Status](https://travis-ci.org/LBNL-UCB-STI/beam.svg?branch=master)](https://travis-ci.org/LBNL-UCB-STI/beam) [all branches](BuildStatus.md)
[![Documentation Status](https://readthedocs.org/projects/beam/badge/?version=latest)](http://beam.readthedocs.io/en/latest/?badge=latest)

The Framework for Modeling Behavior, Energy, Autonomy, and Mobility in Transportation Systems

BEAM extends the [Multi-Agent Transportation Simulation Framework](https://github.com/matsim-org/matsim) (MATSim)
to enable powerful and scalable analysis of urban transportation systems.

## Build
BEAM is a gradle project. To build the project, you are required to run the following command:
```
gradle build
```
It will generate a shadow jar with all dependencies inside build/libs directory.

## Run
Once the `beam.jar` is ready. You can run beam by executing the following command.
```
java -Xmx2g -jar build/libs/beam.jar --config test/input/beamville/beam.conf
```

> You need to set an environment variable named `PWD` to BEAM home.


## Deploy
To run BEAM simulation or experiment on amazon ec2, use following command with some optional parameters.
```
gradle deploy -P[beamConfigs | beamExperiments]=config-or-experiment-file
```
 It can take some parameters from command line, use `-P` to specify the parameter.
 
 - `beamBranch`: To specify the branch for simulation, master is default branch.
 - `beamCommit`: The commit SHA to run simulation. use `HEAD` if you want to run with latest commit.
 - `beamConfigs`: A comma `,` separated list of `beam.conf` files. It should be relative path under the project home.
 - `beamExperiments`: A comma `,` separated list of `experiment.yml` files. It should be relative path under the project home.
 - `beamBatch`: Set to `false` in case you want to run as many instances as number of config/experiment files. Default is `true`.
 - `shutdownWait`: As simulation ends, ec2 instance would automatically terminate. In case you want to use the instance, please specify the wait in minutes, default wait is 30 min. 
 
 To access the ec2 instance, a proper certificate from admin and DNS is required. DNS of ec2 instance can be found in the output log of the command.
 
 To run batch simulation, you can specify the configuration files using parameter like:
 ```
 gradle deploy -PbeamConfigs=test/input/beamville/beam.conf,test/input/sf-light/sf-light.conf
 ```
 
 To run batch experiments, you can specify the experiment files using parameter like:
  ```
  gradle deploy -PbeamExperiments=test/input/beamville/calibration/transport-cost/experiments.yml,test/input/sf-light/calibration/transport-cost/experiments.yml
  ```
 It will start an ec2 instance, using provided configurations and run all simulations in serial. To run all on separate parallel instances, set `beamBatch` to false. At the end of each simulation it uploads the results to s3.
 
> gradle.properties contains default values for all the parameters.

## Documentation
BEAM is documented on [readthedocs](http://beam.readthedocs.io/en/akka/)

## Project website: 
http://beam.lbl.gov/

