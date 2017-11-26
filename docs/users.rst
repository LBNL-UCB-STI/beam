
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

Experiment config generation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

For the purpose of rapid model calibration BEAM supports configuration generation capabilities powered by Jinja templates ( see more http://jinja.pocoo.org/docs/2.10/)
It's been used to create simulation config files such as beam.conf, modeChoiceParameters.xml, etc.

Lets start from building your experiment definitions in experiment.yml ( see example in  test/input/beamville/calibration/experiments.yml).
`experiment.yml` is YAML config file which consists of 3 sections: header, `baseScenario` and `factors`.

The Header defines basic properties of experiment(title, author, etc. ) and several paths to Jinja-based templates of BEAM config files.

    ```
        title: Transport-Cost-Calibration
        author: MyName
        beamTemplateConfPath: test/input/beamville/beam.conf
        runExperimentScript: test/input/beamville/calibration/runExperiment.sh.tpl
        modeChoiceTemplate: test/input/beamville/calibration/modeChoiceParameters.xml.tpl
    ```

Factors defines so called `levels`. You can think about factors as of main influencers( or features) of simulation model( e.g transportation mode cost) while levels are discrete values of each factor.
Usually one should set at least two levels: low and high values of each factor variable, but factor can have as many levels as you want.
Each level of factors and baseScenario defines `params` - set of key,value pairs.
Those keys are either property names from beam.conf or placeholder from template config files.
Param names across factors must be unique, otherwise they will overwrite each other cause each run of experiment contains a level from every factor

First you need to defines all properties and template placeholders in baseScenario and than vary these params in levels.

For example, for beamville calibration, we have defined 3 factors with two levels each. One of levels contains  property `mnl_ride_hailing_cost`.
It appears in modeChoiceParameters.xml.tpl as `{{ mnl_ride_hailing_cost }}`. This placeholder will be replaced during template processing.
Same true for all properties in baseScenario. placeholders for template files must NOT contain dot symbol( due to special behaviour of Jinja with dot).
However it's possible to put full names of properties from `beam.conf` if they need to be overrided within this experiment run.

Also note that `mnl_ride_hailing_cost` appears in baseScenario too. This important concept each level overrides properties from baseScenario.
It's better to keep long self-describing params keys across all levels.

As for now, there is two template files `modeChoiceParameters.xml.tpl` which defines mode choice model params and `runExperiment.sh.tpl` that defines bash script to run individual experiment.
Note:
  All paths in templates and beam.conf should be *relative to project root*.

experiment.xml may defines bash variable that will be available in runExperiment.sh.

Experiment generation can be run using following command from *project root* after project has been built:

```
java -cp build/libs/*:build/resources/main beam.experiment.ExperimentGenerator --experiments test/input/beamville/calibration/experiments.yml

```
It's better to create a new `calibration` folder in your data input directory and put both templates and experiment.yml there.
ExperimentGenerator will create folder structure next to experiment.yml with name of title of experiment and subfolders for each combination of levels( experiment run) including baseScenario run.

Each experiment run folder will contain generated beam.conf ( based on beamTemplateConfPath), modeChoiceParameters.xml and runExperiment.sh
 with placeholders substituted with values from baseScenario or level's params. Obviously level's params override baseScenario params.
The generator also creates  `experiments.csv` next to experiment.yml with mapping between experiment run name, level's params of each experiment and location of configs.

`runExperiment.sh` is executable and can be executed to run individual simulation. Output of simulation will appear in `output` subfolder next to runExperiment.sh
