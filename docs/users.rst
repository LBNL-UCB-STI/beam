
User's Guide
=================

Getting Started
---------------
The following guide is designed as a demonstration of using BEAM and involves running the model as an executable on a scaled population and transportation system. This is the ideal place to familiarize yourself with the basics of configuring and running BEAM as well as doing small scale tests and analysis. 

For more advanced utilization or to contribute to the BEAM project, see the :ref:`developers-guide`.

System Requirements
^^^^^^^^^^^^^^^^^^^

* At least 8GB RAM
* Windows, Mac OSX, Linux
* Java Runtime Environment 1.8
* To verify your JRE: https://stackoverflow.com/questions/8472121/which-jre-i-am-using
* To download JRE 1.8 (AKA JRE 8): http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html
* We also recommend downloading Senozon VIA and obtaining a Free License: https://via.senozon.com/download

Installing
^^^^^^^^^^

Download the latest release of BEAM here:

After you unzip the archive, you will see the following files in the top level of the expanded directory::

  BEAM.exe
  beam.conf
  beamville.conf
  sf-light.conf
  input/
    beamville
    sf-light

Running BEAM
^^^^^^^^^^^^
`BEAM.exe` is the executable which is run by double-clicking. ---describe what happens here---

`beam.conf` is the configuration file that `BEAM.exe` will run, by deafult it is identical to `beamville.conf`. If you want to run the `sf-light` scenario, then replace `beam.conf` with the contents of `sf-light.conf` by copying over the file. 

Scenarios
^^^^^^^^^
The `beamville` test scenario is a toy network consisting of a 4 x 4 block gridded road network, a light rail transit agency, a bus transit agency, and a population of ~50 agents.

.. image:: _static/figs/beamville-net.png

The `sf-light` scenario includes the City of San Francisco road network, the SF Muni public transit service, and a sample population of ~3,000 agents.

.. image:: _static/figs/sf-light.png

Inputs
^^^^^^^

BEAM follows the [MATSim convention](http://archive.matsim.org/docs) for most of the inputs required to run a simulation, though specifying the road network and transit system is based on the [R5 requirements](https://github.com/conveyal/r5). The following is a brief overview of the minimum requirements needed to conduct a BEAM run, more detailed descriptions are available in the :ref:`developers-guide`.

* A configuration file (e.g. `beam.conf`)
* The person population and corresponding attributes files (e.g. `population.xml` and `populationAttributes.xml`)
* The household population and corresponding attributes files (e.g. `households.xml` and `householdAttributes.xml`)
* The personal vehicle fleet (e.g. `vehicles.xml`)
* The definition of vehicle types for the public transit fleet (e.g. `transitVehicles.xml`)
* The mode choice parameters file (e.g. `modeChoiceParameters.xml`)
* A directory containing network and transit data used by R5 (e.g. `r5/`)
* The open street map network (e.g. `r5/beamville.osm`)
* GTFS archives, one for each transit agency (e.g. `r5/bus.zip`)

Outputs
^^^^^^^
At the conclusion of a BEAM run using the default `beamville` scenario, you will see outputs written to ---output location---. The files you should see in this directory are::

  modestats.txt
  scorestats.txt
  stopwatch.png
  stopwatch.txt
  traveldistancestats.txt
  ITERS/
    it.0/
      0.events.csv
      0.legHistogram.txt
      0.physsim-plans.xml
      0.plans.xml.gz
      0.tripdurations.txt
      

Model Config
^^^^^^^^^^^^

To get started, we will focus your attention on a few of the most commonly used and useful configuration parameters that control beam.

Model Config
^^^^^^^^^^^^



Experiment Manager
------------------

BEAM features a flexible experiment manager which allows users to conduct multi-factorial experiments with minimal configuration. The tool is powered by Jinja templates ( see more http://jinja.pocoo.org/docs/2.10/).

We have created two example experiments to demonstrate how to use the experiment manager. The first is a simple 2-factorial experiment that varies some parameters of scientific interest. The second involves varying parameters of the mode choice model as one might do in a calibration exercise. 

In any experiment, we seek to vary the parameters of BEAM systematically and producing results in an organized, predicable location to facilitate post-processing. For the two factor experiment example, we only need to vary the contents of the BEAM config file (beam.conf) in order to achieve the desired anlaysis.

Lets start from building your experiment definitions in experiment.yml ( see example in `test/input/beamville/example-experiment/experiment.yml`).
`experiment.yml` is a YAML config file which consists of 3 sections: header, defaultParams, and factors.

The Header defines the basic properties of the experiment, the title, author, and a path to the configuration file (paths should be relative to the project root)::

  title: Example-Experiment
  author: MyName
  beamTemplateConfPath: test/input/beamville/beam.conf

The Default Params are used to override any parameters from the BEAM config file for the whole experiment. These values can, in turn, be overridden by factor levels if specified. This section is mostly a convenient way to ensure certain parameters take on specific values without modifying the BEAM config file in use.

Experiments consist of 'factors', which are a dimension along which you want to vary parameters. Each instance of the factor is a level. In our example, one factor is "transitCapacity" consisting of two levels, "Low" and "High". You can think about factors as of main influencers (or features) of simulation model while levels are discrete values of each factor.

Factors can be designed however you choose, including adding as many factors or levels within those factors as you want. E.g. to create a 3 x 3 experimental design, you would set three levels per factor as in the example below::

  factors:
    - title: transitCapacity
      levels:
      - name: Low
        params:
          beam.agentsim.tuning.transitCapacity: 0.01
      - name: Base
        params:
          beam.agentsim.tuning.transitCapacity: 0.05
      - name: High
        params:
          beam.agentsim.tuning.transitCapacity: 0.1

    - title: ridehailNumber
      levels:
      - name: Low
        params:
          beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation: 0.001
      - name: Base
        params:
          beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation: 0.01
      - name: High
        params:
          beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation: 0.1

Each level and the baseScenario defines `params`, or a set of key,value pairs. Those keys are either property names from beam.conf or placeholders from any template config files (see below for an example of this). Param names across factors and template files must be unique, otherwise they will overwrite each other.

In our second example (see directory `test/input/beamville/example-calibration/`), we have added a template file `modeChoiceParameters.xml.tpl` that allows us to change the values of parameters in BEAM input file `modeChoiceParameters.xml`. In the `experiment.yml` file, we have defined 3 factors with two levels each. One level contains the property `mnl_ride_hailing_intercept`, which appears in modeChoiceParameters.xml.tpl as `{{ mnl_ride_hailing_intercept }}`. This placeholder will be replaced during template processing. The same is true for all properties in the defaultParams and under the facts. Placeholders for template files must NOT contain the dot symbol due to special behaviour of Jinja. However it is possible to use the full names of properties from `beam.conf` (which *do* include dots) if they need to be overridden within this experiment run.

Also note that `mnl_ride_hailing_intercept` appears both in the level specification and in the baseScenario. When using a template file (versus a BEAM Config file), each level can only override properties from Default Params section of `experiment.yml`.

Experiment generation can be run using following command from *project root* after the project has been compiled::

  gradle assemble

  java -cp build/libs/*:build/resources/main beam.experiment.ExperimentGenerator --experiments test/input/beamville/example-experiment/experiments.yml

It's better to create a new sub-folder folder (e.g. 'calibration' or 'experiment-1') in your data input directory and put both templates and the experiment.yml there.
The ExperimentGenerator will create a sub-folder next to experiment.yml named `runs` which will include all of the data needed to run the experiment along with a shell script to execute a local run. The generator also creates an `experiments.csv` file next to experiment.yml with a mapping between experimental group name, the level name and the value of the params associated with each level. 

Within each run sub-folder you will find the generated BEAM config file (based on beamTemplateConfPath), any files from the template engine (e.g. `modeChoiceParameters.xml`) with all placeholders properly substituted, and a `runBeam.sh` executable which can be used to execute an individual simulation. The outputs of each simulation will appear in the `output` subfolder next to runBeam.sh


Automated Cloud Deployment
--------------------------

To run BEAM simulation or experiment on amazon ec2, use following command with some optional parameters::

  gradle deploy -P[beamConfigs | beamExperiments]=config-or-experiment-file

It can take some parameters from command line, use `-P` to specify the parameter.

* `beamBranch`: To specify the branch for simulation, master is default branch.
* `beamCommit`: The commit SHA to run simulation. use `HEAD` if you want to run with latest commit.
* `beamConfigs`: A comma `,` separated list of `beam.conf` files. It should be relative path under the project home.
* `beamExperiments`: A comma `,` separated list of `experiment.yml` files. It should be relative path under the project home.
* `beamBatch`: Set to `false` in case you want to run as many instances as number of config/experiment files. Default is `true`.
* `shutdownWait`: As simulation ends, ec2 instance would automatically terminate. In case you want to use the instance, please specify the wait in minutes, default wait is 30 min.

To access the ec2 instance, a proper certificate from admin and DNS is required. DNS of ec2 instance can be found in the output log of the command.

To run batch simulation, you can specify the configuration files using parameter like::

  gradle deploy -PbeamConfigs=test/input/beamville/beam.conf,test/input/sf-light/sf-light.conf


To run batch experiments, you can specify the experiment files using parameter like::

  gradle deploy -PbeamExperiments=test/input/beamville/calibration/transport-cost/experiments.yml,test/input/sf-light/calibration/transport-cost/experiments.yml

It will start an ec2 instance, using provided configurations and run all simulations in serial. To run all on separate parallel instances, set `beamBatch` to false. At the end of each simulation it uploads the results to s3.

  gradle.properties contains default values for all the parameters.
