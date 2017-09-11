# BEAM

[![Build Status](https://travis-ci.org/LBNL-UCB-STI/beam.svg?branch=master)](https://travis-ci.org/LBNL-UCB-STI/beam) [all branches](BuildStatus.md)

The Framework for Modeling Behavior, Energy, Autonomy, and Mobility in Transportation Systems

BEAM extends the [Multi-Agent Transportation Simulation Framework](https://github.com/matsim-org/matsim) (MATSim)
to enable powerful and scalable analysis of urban transportation systems.

## Build
BEAM is a gradle project. To build the project, you are required to run the following command:
````
gradle build
````
It will generate a shadow jar with all dependencies inside build/libs directory.

## Run
Once the `beam.jar` is ready. You can run beam by executing following command.
````
java -Xmx2g -jar build/libs/beam.jar --config test/input/beamville/beam.conf
````

> You need to set an environment variable named `PWD` to BEAM home.

## Documentation
BEAM is documented on [readthedocs](http://beam.readthedocs.io/en/akka/)

## Project website: 
http://beam.lbl.gov/

