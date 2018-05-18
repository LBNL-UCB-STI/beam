
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

Finally, if you want to run the gradle tasks from IntelliJ in OS X, you need to configure your variables as launch tasks by creating a plist file for each. The files should be located under :code:`~/Library/LaunchAgents/` and look like the following. Note that after creating the files you need to log out / log in to OS X and you can't Launch IntelliJ automatically on log-in because the LaunchAgents might not complete in time.

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


GIT-LFS Configuration
^^^^^^^^^^^^^^^^^^^^^

The installation process for git-lfs(v2.3.4, latest installer has some issue with node-git-lfs) client is vey simple and document in detail on `github guide`_ for Mac, windows and Linux.

.. _github guide: https://help.github.com/articles/installing-git-large-file-storage/

To verify successful installation execute following command::

    $ git lfs install
    Git LFS initialized.

To confirm that you have installed the correct version of client run the following command::

   $ git lfs env
   
To replaces the text pointers with the actual files run the following command(if it requests credentials, use any username and leave the password empty)::

   $ git lfs pull
   Git LFS: (98 of 123 files) 343.22 MB / 542.18 MB
   
Automated Cloud Deployment
^^^^^^^^^^^^^^^^^^^^^^^^^^

..

    This functionality is available for core BEAM development team with Amazon Web Services access privileges. Please contact Colin_ for access to capability.

To run a BEAM simulation or experiment on amazon ec2, use following command with some optional parameters::

  gradle deploy -P[beamConfigs | beamExperiments]=config-or-experiment-file

The command will start an ec2 instance based on the provided configurations and run all simulations in serial. At the end of each simulation/experiment, outputs are uploaded to a public Amazon S3 bucket_. To run each each simulation/experiment parallel on separate instances, set `beamBatch` to false. For customized runs, you can also use following parameters that can be specified from command line:

* **beamBranch**: To specify the branch for simulation, current source branch will be used as default branch.
* **beamCommit**: The commit SHA to run simulation. use `HEAD` if you want to run with latest commit, default is `HEAD`.
* **beamConfigs**: A comma `,` separated list of `beam.conf` files. It should be relative path under the project home. You can create branch level defaults by specifying the branch name with `.configs` suffix like `master.configs`. Branch level default will be used if `beamConfigs` is not present.
* **beamExperiments**: A comma `,` separated list of `experiment.yml` files. It should be relative path under the project home.You can create branch level defaults same as configs by specifying the branch name with `.experiments` suffix like `master.experiments`. Branch level default will be used if `beamExperiments` is not present. `beamConfigs` has priority over this, in other words, if both are provided then `beamConfigs` will be used.
* **beamBatch**: Set to `false` in case you want to run as many instances as number of config/experiment files. Default is `true`.
* **region**: Use this parameter to select the AWS region for the run, all instances would be created in specified region. Default `region` is `us-east-2`.
* **shutdownWait**: As simulation ends, ec2 instance would automatically terminate. In case you want to use the instance, please specify the wait in minutes, default wait is 30 min.

If any of the above parameter is not specified at the command line, then default values are assumed for optional parameters. These default values are specified in gradle.properties_ file.

To run a batch simulation, you can specify multiple configuration files separated by commas::

  gradle deploy -PbeamConfigs=test/input/beamville/beam.conf,test/input/sf-light/sf-light.conf

Similarly for experiment batch, you can specify comma-separated experiment files::

  gradle deploy -PbeamExperiments=test/input/beamville/calibration/transport-cost/experiments.yml,test/input/sf-light/calibration/transport-cost/experiments.yml

For demo and presentation material, please follow the link_ on google drive.

.. _Colin: mailto:colin.sheppard@lbl.gov
.. _bucket: https://s3.us-east-2.amazonaws.com/beam-outputs/
.. _gradle.properties: https://github.com/LBNL-UCB-STI/beam/blob/master/gradle.properties
.. _link: https://goo.gl/Db37yM

Performance Monitoring
^^^^^^^^^^^^^^^^^^^^^^

Beam uses `Kamon`_ as a performance monitoring framework. It comes with a nice API to instrument your application code for metric recoding. Kamon also provide many different pingable recorders like Log Reporter, StatsD, InfluxDB etc. You can configure your desired recorder with project configurations under Kamon/metrics section. When you start the application it will measure the instrumented components and recorder would publish either to console or specified backend where you can monitor/analyse the metrics.

Beam Metrics Utility (`MetricsSupport`)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Beam provides metric utility as part of performance monitoring framework using Kamon API. It makes developers life very easy, all you need is to extend your component from `beam.sim.metrics.MetricsSupport` trait and call your desired utility. As you extend the trait, it will add some handy entity recorder methods in your component, to measure the application behaviour. By using `MetricSupport` you measure following different metricises.

    - Count occurrences or number of invocation::

        countOccurrence("number-of-routing-requests", Metrics.VerboseLevel)

    In this example first argument of `countOccurrence` is the name of entity you want to record and second is the metric level. It is the simplest utility and just counts and resets to zero upon each flush. you can use it for counting errors or occurrences of specifics events.

    - Execution time of some expression, function call or component::

        latency("time-to-calculate-route", Metrics.RegularLevel) {
            calcRoute(request)
        }

    In this snippet, first two arguments are same as of `countOccurrence`. Next, it takes the actual piece of code/expression for which you want to measure the execution time/latency. In the example above we are measuring the execution time to calculate a router in `R5RoutingWorker`, we named the entity as `"request-router-time"` and set metric level to `Metrics.RegularLevel`. When this method executes your entity recorder record the metrics and log with provided name.

Beam Metrics Configuration
~~~~~~~~~~~~~~~~~~~~~~~~~~

After instrumenting your code you need configure your desired metric level, recorder backends and other Kamon configurations in your project configuration file (usually beam.conf). Update your metrics configurations as below::

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
            hostname = 127.0.0.1  # replace with your container in case local loop didn't work
            port = 8125
        }

        influxdb {
            hostname = 18.216.21.254   # specify InfluxDB server IP
            port = 8089
            protocol = "udp"
        }

        modules {
            #kamon-log-reporter.auto-start = yes
            #kamon-statsd.auto-start = yes
            #kamon-influxdb.auto-start = yes
        }
    }

Make sure to update the **host** and **port** for StatsD or InfluxDB (either one(or both) of them you are using) with its relevant the server IP address in the abode config.

Other then IP address you also need to confirm few thing in your environment like.

-  beam.metrics.level would not be pointing to the value `off`.
-  kamon-statsd.auto-start = yes, under kamon.modules.
-  build.gradle(Gradle build script) has kamon-statsd, kamon-influxdb or kamon-log-reporter available as dependencies, based on your kamon.modules settings and desired backend/logger.


Setup Docker as Metric Backend
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Kamon's `StatsD`_ reporter enables beam to publish matrices to a verity of backends. `Graphite`_ as the StatsD backend and `Grafana`_ to create beautiful dashboards build a very good monitoring ecosystem. To make environment up and running in a few minutes, use Kamon's provided docker image (beam dashboard need to import) from `docker hub`_ or build using Dockerfile and supporting configuration files available in metrics directory under beam root. All you need is to install few prerequisite like docker, docker-compose, and make. To start a container you just need to run the following command in metrics directory (available at root of beam project)::

    $ make up

With the docker container following services start and exposes the listed ports:

* 80: the Grafana web interface.
* 81: the Graphite web port
* 2003: the Graphite data port
* 8125: the StatsD port.
* 8126: the StatsD administrative port.

Now your docker container is up and required components are configured, all you need to start beam simulation. As simulation starts, kamon would load its modules and start publishing metrics to the StatsD server (running inside the docker container).

In your browser open http://localhost:80 (or with IP you located in previous steps). Login with the default username (admin) and password (admin), open existing beam dashboard (or create a new one).

If you get the docker image from docker hub, you need to import the beam dashboard from metrics/grafana/dashboards directory.

- To import a dashboard open dashboard search and then hit the import button.
- From here you can upload a dashboard json file, as upload complete the import process will let you change the name of the dashboard, pick graphite as data source.
- A new dashboard will appear in dashboard list.

Open beam dashboard (or what ever the name you specified while importing) and start monitoring different beam module level matrices in a nice graphical interface.

To view the container log::

    $ make tail

To stop the container::

    $ make down


Cloud visualization services become more popular nowadays and save much effort and energy to prepare an environment. In future we are planing to use `Datadog`_ (a cloud base monitoring and analytic platform) with beam. `Kamon Datadog integration`_ is the easiest way to have something (nearly) production ready.


How to get Docker IP?
*********************

Docker with VirtualBox on macOS/Windows: use docker-machine IP instead of localhost. To find the docker container IP address, first you need to list the containers to get container id using::

    $ docker ps

Then use the container id to find IP address of your container. Run the following command by providing container id in following command by replacing YOUR_CONTAINER_ID::

    $ docker inspect YOUR_CONTAINER_ID

Now at the bottom, under NetworkSettings, locate IP Address of your docker container.



.. _Kamon: http://kamon.io
.. _StatsD: http://kamon.io/documentation/0.6.x/kamon-statsd/overview/
.. _Graphite: http://graphite.wikidot.com/
.. _Grafana: http://grafana.org/
.. _docker hub: https://hub.docker.com/u/kamon/
.. _Datadog: https://www.datadoghq.com/
.. _Kamon Datadog integration: http://kamon.io/documentation/kamon-datadog/0.6.6/overview/


Tagging Tests for Periodic CI
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

ScalaTest allows you to define different test categories by tagging your tests. These tags categorise tests in different sets. And later you can filter these set of tests by specifying these tags with your build tasks. Beam also provide a custom tag `Periodic` to mark your tests for periodic CI runs. As you mark the test with this tag, your test would be included automatically into execution set and become the part of next scheduled run. It also be excluded immediately for regular gradle test task and CI. Follow the example below to tag your test with `Periodic` tag::

   behavior of "Trajectory"
      it should "interpolate coordinates" taggedAs Periodic in {
         ...
      }

This code marks the test with `com.beam.tags.Periodic` tag. You can also specify multiple tags as a comma separated parameter list in `taggedAs` method. Following code demonstrate the use of multiple tags::

   "The agentsim" must {
      ...

      "let everybody walk when their plan says so" taggedAs (Periodic, Slow) in {
         ...
      }

      ...
   }

You can find details about scheduling a continuous integration build under DevOps section `Configure Periodic Jobs`_.

.. _Configure Periodic Jobs: http://beam.readthedocs.io/en/latest/devops.html#configure-periodic-jobs
