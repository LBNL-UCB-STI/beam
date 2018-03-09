
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
* To verify your JRE: https://www.java.com/en/download/help/version_manual.xml
* To download JRE 1.8 (AKA JRE 8): http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html
* We also recommend downloading Senozon VIA and obtaining a Free License: https://via.senozon.com/download

Installing
^^^^^^^^^^

Download `BEAM v0.5`_.

.. _BEAM v0.5: https://github.com/LBNL-UCB-STI/beam/releases/download/v0.5.0/beam-gui.zip

After you unzip the archive, you will see a directory that looks like this when partially expanded: 

.. image:: _static/figs/beam-gui-files.png

For Windows, double click `bin/beam-gui.bat`, on UNIX-like systems, double-click `bin/beam-gui`.

Running BEAM
^^^^^^^^^^^^
The BEAM GUI app is the simplest way to run the model. It looks like this:

.. image:: _static/figs/beam-gui.png

Use "Choose" to select a configuration file from your file system. Choose `input/beamville/beam.conf`. 

Click "Run BEAM". 

You will see output appear in the console. Congrats, you're running BEAM! 

Click "Open" next to the Output Directory text box and you should see results appear in a sub-folder called "beamville_%DATE_TIME%".

Scenarios
^^^^^^^^^
We have provided two scenarios for you to explore under the `input` directory.

The `beamville` test scenario is a toy network consisting of a 4 x 4 block gridded road network, a light rail transit agency, a bus transit agency, and a population of ~50 agents.

.. image:: _static/figs/beamville-net.png

The `sf-light` scenario is based on the City of San Francisco, including the SF Muni public transit service and a range of sample populations from 500 to 25,000 agents.

.. image:: _static/figs/sf-light.png

Inputs
^^^^^^^

BEAM follows the `MATSim convention`_ for most of the inputs required to run a simulation, though specifying the road network and transit system is based on the `R5 requirements`_. The following is a brief overview of the minimum requirements needed to conduct a BEAM run. 

.. _MATSim convention: http://archive.matsim.org/docs
.. _R5 requirements: https://github.com/conveyal/r5

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
At the conclusion of a BEAM run using the default `beamville` scenario, you will see outputs written to the location as listed in the "Output Directory" text box. The files you in the output sub-folder should look like this when the run is complete:

.. image:: _static/figs/beamville-outputs.png

Each iteration of the run produces a sub-folder under the `ITERS` directory. Within these, several automatically generated outputs are written including plots of modal usage, TNC dead heading, and energy consumption by mode. 

In addition, raw outputs are available in the two events file (one from the AgentSim and one from the PhysSim, see :ref:`matsim-events` for more details), titled `%ITER%.events.csv` and `%ITER%.physSimEvents.xml.gz` respectively.

Model Config
^^^^^^^^^^^^

To get started, we will focus your attention on a few of the most commonly used and useful configuration parameters that control beam::

  # Ride Hailing Params
  beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation=0.05
  beam.agentsim.agents.rideHailing.defaultCostPerMile=1.25
  beam.agentsim.agents.rideHailing.defaultCostPerMinute=0.75
  # Scaling and Tuning Params; 1.0 results in no scaling
  beam.agentsim.tuning.transitCapacity = 0.2
  beam.agentsim.tuning.transitPrice = 1.0
  beam.agentsim.tuning.tollPrice = 1.0
  beam.agentsim.tuning.rideHailPrice = 1.0

* numDriversAsFractionOfPopulation - Defines the # of ride hailing drivers to create. Drivers begin the simulation located at or near the homes of existing agents, uniformly distributed.
* defaultCostPerMile - One component of the 2 part price of ride hail calculation.
* defaultCostPerMinute - One component of the 2 part price of ride hail calculation.
* transitCapacity - Scale the number of seats per transit vehicle... actual seats are rounded to nearest whole number. Applies uniformly to all transit vehilces.
* transitPrice - Scale the price of riding on transit. Applies uniformly to all transit trips.
* tollPrice - Scale the price to cross tolls.
* rideHailPrice - Scale the price of ride hailing. Applies uniformly to all trips and is independent of defaultCostPerMile and defaultCostPerMinute described above. I.e. price = (costPerMile + costPerMinute)*rideHailPrice

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

Experiment generation can be run using following command::

  gradle -PmainClass=beam.experiment.ExperimentGenerator -PappArgs="['--experiments', 'test/input/beamville/example-experiment/experiment.yml']" execute

It's better to create a new sub-folder folder (e.g. 'calibration' or 'experiment-1') in your data input directory and put both templates and the experiment.yml there.
The ExperimentGenerator will create a sub-folder next to experiment.yml named `runs` which will include all of the data needed to run the experiment along with a shell script to execute a local run. The generator also creates an `experiments.csv` file next to experiment.yml with a mapping between experimental group name, the level name and the value of the params associated with each level. 

Within each run sub-folder you will find the generated BEAM config file (based on beamTemplateConfPath), any files from the template engine (e.g. `modeChoiceParameters.xml`) with all placeholders properly substituted, and a `runBeam.sh` executable which can be used to execute an individual simulation. The outputs of each simulation will appear in the `output` subfolder next to runBeam.sh

Performance Monitoring
----------------------
Beam uses `Kamon`_ as a performance monitoring framework, and its `StatsD`_ reporter enables beam to publish matrices to a verity of backends. `Graphite`_ as the StatsD backend and `Grafana`_ to create beautiful dashboards build a very good monitoring ecosystem. To make environment up and running in a few minutes, use Kamon's provided docker image (beam dashboard need to import) from from `docker hub`_ or build using Dockerfile and supporting configuration files available in metrics directory under beam root. All you need is to install few prerequisite like docker, docker-compose, and make. To start a container you just need to run the following command in metrics dir::

   $ make up

.. _Kamon: http://kamon.io
.. _StatsD: http://kamon.io/documentation/0.6.x/kamon-statsd/overview/
.. _Graphite: http://graphite.wikidot.com/
.. _Grafana: http://grafana.org/
.. _docker hub: https://hub.docker.com/u/kamon/


With the docker container following services start and exposes the listed ports:

* 80: the Grafana web interface.
* 81: the Graphite web port
* 2003: the Graphite data port
* 8125: the StatsD port.
* 8126: the StatsD administrative port.

Now start beam by specifying metrics configurations in beam.conf and update the host and port for StatsD server in following config::

  beam.metrics.level = "verbose"

  kamon {
      trace {
        level = simple-trace
      }

      metric {
        #tick-interval = 5 seconds
        filters {
          trace.includes = [ "**" ]

          akka-actor {
            includes = [ "beam-actor-system/user/router/**", "beam-actor-system/user/worker-*" ]
            excludes = [ "beam-actor-system/system/**", "beam-actor-system/user/worker-helper" ]
          }

          akka-dispatcher {
            includes = [ "beam-actor-system/akka.actor.default-dispatcher" ]
          }
        }
      }

      statsd {
        hostname = 192.168.99.100
        port = 8125
      }

      modules {
        #kamon-log-reporter.auto-start = yes
        kamon-statsd.auto-start = yes
      }
    }

Once your container is running all you need to do is, to make sure beam.metrics.level would not be pointing to the value `off` and kamon.statsd.hostname has IP of your docker container and start beam simulation. As simulation starts, kamon would load its modules and start publishing metrics to the StatsD server, running inside the docker container. To open your browser pointing to http://localhost:80 (Docker with VirtualBox on macOS/Windows: use docker-machine ip instead of localhost). Login with the default username (admin) and password (admin), open existing beam dashboard (or create a new one).

To view the container log::

   $ make tail

To stop the container::

   $ make down

If you get the image from docker hub, you need to import the beam dashboard from metrics/grafana/dashboards directory.

Cloud visualization services become more popular nowadays and save lost of effort and energy to prepare an environment. In future we are planing to use `Datadog`_ (a cloud base monitoring and analytic platform) with beam. `Kamon Datadog integration`_ is the easiest way to have something (nearly) production ready.
.. _Datadog: https://www.datadoghq.com/
.. _Kamon Datadog integration: http://kamon.io/documentation/kamon-datadog/0.6.6/overview/

