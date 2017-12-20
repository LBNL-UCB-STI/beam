
Users' Guide
=================

For now, the user guide maintains a focus on PEVs. 

If you want to run a BEAM model for your region, you will need the following data sets (real or synthetic):

* Road Network
* Travel Activity for your Virtual Population
* Charging Infrastructure
* Vehicle Characteristics and Composition


Installing
^^^^^^^^^^

Instructions on downloading and using the BEAM executable coming soon... 

Model Config
^^^^^^^^^^^^

Instructions on downloading and using the BEAM executable coming soon... 

Experiment Manager
^^^^^^^^^^^^^^^^^^

BEAM features a flexible experiment manager which allows users to conduct multi-factorial experiments with minimal configuration. The tool is powered by Jinja templates ( see more http://jinja.pocoo.org/docs/2.10/).

To demonstrate how to use the experiment manager, we will use parameter calibration as an example. In this case, the experiment is to vary the parameters of the mode choice model systematically in order to reproduce observed modal splits in the transportation system. This requires modifying the overall BEAM config file (beam.conf) as well as the mode choice parameters file (modeChoiceParameters.xml).

Lets start from building your experiment definitions in experiment.yml ( see example in  test/input/beamville/calibration/experiments.yml).
`experiment.yml` is YAML config file which consists of 3 sections: header, `baseScenario` and `factors`.

The Header defines the basic properties of the experiment (title, author, etc.) and several paths (all relative to the project root) to Jinja-based templates of BEAM config files.

    ```
        title: Transport-Cost-Calibration
        author: MyName
        beamTemplateConfPath: test/input/beamville/beam.conf
        runExperimentScript: test/input/beamville/calibration/runExperiment.sh.tpl
        modeChoiceTemplate: test/input/beamville/calibration/modeChoiceParameters.xml.tpl
    ```

Experiments consist of 'factors', which are a dimension along which you want to vary parameters. Each instance of the factor is a level. E.g. a factor could be "Transit Price" consisting of two levels, "Low" and "High". You can think about factors as of main influencers (or features) of simulation model while levels are discrete values of each factor.

Usually one should set at least two levels per factor (in addition to the Base Level). But factors can have as many levels as you want. Each level and the baseScenario defines `params`, or a set of key,value pairs. Those keys are either property names from beam.conf or placeholders from the template config files. Param names across factors must be unique, otherwise they will overwrite each other.

First you need to defines all properties and template placeholders in baseScenario and then you vary any subset of these params in each level.

For example, for beamville calibration, we have defined 3 factors with two levels each. One level contains the property `mnl_ride_hailing_cost`, which appears in modeChoiceParameters.xml.tpl as `{{ mnl_ride_hailing_cost }}`. This placeholder will be replaced during template processing. The same is true for all properties in the baseScenario. Placeholders for template files must NOT contain dot symbol( due to special behaviour of Jinja with dot). However it is possible to use the full names of properties from `beam.conf` (which *do* include dots) if they need to be overrided within this experiment run.

Also note that `mnl_ride_hailing_cost` appears both in the level specification and in the baseScenario. This is important, each level can only override properties from the baseScenario.

As for now, there are two template files `modeChoiceParameters.xml.tpl` which defines mode choice model params and `runExperiment.sh.tpl` which defines the bash script to run the actual experiment. 

experiment.xml may defines bash variables that will be available in runExperiment.sh.

Experiment generation can be run using following command from *project root* after the project has been compiled: 

```
gradle assemble

java -cp build/libs/*:build/resources/main beam.experiment.ExperimentGenerator --experiments test/input/beamville/calibration/experiments.yml
```

It's better to create a new sub-folder folder (e.g. 'calibration' or 'experiment-1') in your data input directory and put both templates and the experiment.yml there.
The ExperimentGenerator will create a folder structure next to experiment.yml named with the title of the experiment and a subfolder for each combination of levels (also referred to as an "experimentinal group") including the baseScenario run.

Each experiment run folder will contain the generated beam.conf (based on beamTemplateConfPath), modeChoiceParameters.xml and runExperiment.sh with all placeholders properly substituted.params. The generator also creates an `experiments.csv` file next to experiment.yml with a mapping between experimental group name, the level name and the value of the params associated with each level. 

`runExperiment.sh` is executable and can be executed to run any individual simulation. The output of simulation will appear in the `output` subfolder next to runExperiment.sh
