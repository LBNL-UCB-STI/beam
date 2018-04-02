
.. _developers-guide:

Developer's Guide
=================

.. IntelliJ IDEA Setup
   ^^^^^^^^^^

Repositories
^^^^^^^^^^^^^
The beam repository on github `is here. <https://github.com/LBNL-UCB-STI/beam>`_

The convention for merging into the master branch is that master needs to be pass all tests and at least one other active BEAM developer needs to review your changes before merging. Please do this by creating a pull request from any new feature branches into master. We also encourage you to create pull requests early in your development cycle which gives other's an opportunity to observe and/or provide feedback in real time. When you are ready for a review, invite one or more through the pull request. 

Please use the following naming convention for feature branches, "<initials-or-username>/<descriptive-feature-branch-name>". Adding the issue number is also helpful, e.g.:

cjrs/issue112-update-docs

An example workflow for contributing a new feature beam might look like this:

+ create a new branch off of master (e.g. cjrs/issue112-update-docs)
+ push and create a pull request right away
+ work in cjrs/issue112-update-docs
+ get it to compile, pass tests
+ request reviews from pull request
+ after reviews and any subsequent iterations, merge into master and close pull request
+ delete feature branch unless continued work to happy imminently on same feature branch

The pev-only and related feature branches hold a previous version of BEAM (v0.1.X) which is incompatible with master but is still used for modeling and analysis work.

Configuration
^^^^^^^^^^^^^

We use `typesafe config <https://github.com/typesafehub/config>`_ for our configuration parser and we use the `tscfg <https://github.com/carueda/tscfg>`_ utility for generating typesafe container class for our config that we can browse with auto-complete while developing.

Then you can make a copy of the config template under::

  src/main/resources/config-template.conf

and start customizing the configurations to your use case.

To add new parameters or change the structure of the configuration class itself, simply edit the `config-template.conf` file and run the gradle task::

  gradle generateConfig

This will generate a new class `src/main/scala/beam/metasim/config/BeamConfig.scala` which will reflect the new structure and parameters.

Environment Variables
^^^^^^^^^^^^^^^^^^^^^

BEAM supports using an environment variable to optionally specify a directory to write outputs. This is not required.

Depending on your operating system, the manner in which you want to run the BEAM application or gradle tasks, the specific place where you set these variables will differ. To run from the command line, add these statements to your .bash_profile file::

  export BEAM_OUTPUT=/path/to/your/preferred/output/destination/`

To run from IntelliJ as an "Application", edit the "Environment Variables" field in your Run Configuration to look like this::

  BEAM_OUTPUT="/path/to/your/preferred/output/destination/"

Finally, if you want to run the gradle tasks from IntelliJ in OS X, you need to configure you variables as launch tasks by creating a plist file for each. The files should be located under :code:`~/Library/LaunchAgents/` and look like the following. Note that after creating the files you need to log out / log in to OS X and you can't Launch IntelliJ automatically on log-in because the LaunchAgents might not complete in time.

File: :code:`~/Library/LaunchAgents/setenv.BEAM_OUTPUT.plist`::

    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
      <dict>
        <key>Label</key>
        <string>setenv.BEAM_OUTPUT</string>
        <key>ProgramArguments</key>
        <array>
          <string>/bin/launchctl</string>
          <string>setenv</string>
          <string>BEAM_OUTPUT</string>
          <string>/path/to/your/preferred/output/destination/</string>
        </array>
        <key>RunAtLoad</key>
        <true/>
        <key>ServiceIPC</key>
        <false/>
      </dict>
    </plist>

Automated Cloud Deployment
^^^^^^^^^^^^^^^^^^^^^^^^^^

This functionality is available to internal BEAM developers with Amazon Web Services access privileges. Please contact Colin_ to discuss how you might access this capability or set up your own cloud deployment capability.

.. _Colin: mailto:colin.sheppard@lbl.gov

To run a BEAM simulation or experiment on amazon ec2, use following command with some optional parameters::

  gradle deploy -P[beamConfigs | beamExperiments]=config-or-experiment-file

The following optional parameters can be specified from command line:

* `beamBranch`: To specify the branch for simulation, current source branch will be used as default branch.
* `beamCommit`: The commit SHA to run simulation. use `HEAD` if you want to run with latest commit, default is `HEAD`.
* `beamConfigs`: A comma `,` separated list of `beam.conf` files. It should be relative path under the project home. You can create branch level defaults by specifying the branch name with `.configs` suffix like `master.configs`. Branch level default will be used if `beamConfigs` is not present.
* `beamExperiments`: A comma `,` separated list of `experiment.yml` files. It should be relative path under the project home.You can create branch level defaults same as configs by specifying the branch name with `.experiments` suffix like `master.experiments`. Branch level default will be used if `beamExperiments` is not present. `beamConfigs` has priority over this, in other words, if both are provided then `beamConfigs` will be used.
* `beamBatch`: Set to `false` in case you want to run as many instances as number of config/experiment files. Default is `true`.
* `region`: Use this parameter to select the AWS region for the run, all instances would be created in specified region. Default `region` is `us-east-2`.
* `shutdownWait`: As simulation ends, ec2 instance would automatically terminate. In case you want to use the instance, please specify the wait in minutes, default wait is 30 min.

If any of the above parameter is not specified at the command line, then default values are assumed for the above optional parameters. These default values are contained in the project gradle.properties_ file.

.. _gradle.properties: https://github.com/LBNL-UCB-STI/beam/blob/master/gradle.properties

To run a manually specified batch simulation, you can specify multiple configuration files separated by commas::

  gradle deploy -PbeamConfigs=test/input/beamville/beam.conf,test/input/sf-light/sf-light.conf

To run experiments, you can specify comma-separated experiment files::

  gradle deploy -PbeamExperiments=test/input/beamville/calibration/transport-cost/experiments.yml,test/input/sf-light/calibration/transport-cost/experiments.yml

The command will start an ec2 instance based on the provided configurations and run all simulations in serial. To run on separate parallel instances, set `beamBatch` to false. At the end of each simulation, outputs are uploaded to Amazon S3.


Performance Monitoring
^^^^^^^^^^^^^^^^^^^^^^

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

