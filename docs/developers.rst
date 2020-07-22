
.. _developers-guide:

Developer's Guide
=================

.. IntelliJ IDEA Setup
   ^^^^^^^^^^

Repositories
^^^^^^^^^^^^
The beam repository on github `is here. <https://github.com/LBNL-UCB-STI/beam>`_

The convention for merging into the develop branch is that develop needs to be pass all tests and at least one other active BEAM developer needs to review your changes before merging. Please do this by creating a pull request from any new feature branches into develop. We also encourage you to create pull requests early in your development cycle which gives other's an opportunity to observe and/or provide feedback in real time. When you are ready for a review, invite one or more through the pull request.

Please use the following naming convention for feature branches, "<initials-or-username>/<descriptive-feature-branch-name>". Adding the issue number is also helpful, e.g.:

cjrs/#112-update-docs

An example workflow for contributing a new feature beam might look like this:

+ create a new branch off of develop (e.g. cjrs/#112-update-docs)
+ push and create a pull request right away
+ work in cjrs/#112-update-docs
+ get it to compile, pass tests
+ request reviews from pull request
+ after reviews and any subsequent iterations, merge into develop and close pull request
+ delete feature branch unless continued work to happy imminently on same feature branch

The pev-only and related feature branches hold a previous version of BEAM (v0.1.X) which is incompatible with develop but is still used for modeling and analysis work.

Configuration
^^^^^^^^^^^^^

We use `typesafe config <https://github.com/typesafehub/config>`_ for our configuration parser and we use the `tscfg <https://github.com/carueda/tscfg>`_ utility for generating typesafe container class for our config that we can browse with auto-complete while developing.

Then you can make a copy of the config template under::

  src/main/resources/config-template.conf

and start customizing the configurations to your use case.

To add new parameters or change the structure of the configuration class itself, simply edit the `config-template.conf` file and run the gradle task::

  ./gradlew generateConfig

This will generate a new class `src/main/scala/beam/sim/config/BeamConfig.scala` which will reflect the new structure and parameters.

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

GIT-LFS - known issues
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
** IntelliJ IDEA credentials issue **

Sometimes it might be possible that IntelliJ IDEA integration struggles with the credentials of git-lfs if they are different from your regular git credentials (which is most probably the case for beam). Hence if you changed files in beam that are tracked by git-lfs (e.g. vehicles.csv.gz) you should use command line git for pushing them to the server.

** timeout **

Sometimes it is possible to face a timeout issue when trying to push huge files. The steps below can be followed:

#. Connect to some EC2 server inside the same Amazon S3 region: us-east-2

#. Copy the file to the server using scp::

   $ scp -i mykey.pem somefile.txt remote_username@machine.us-east-2.compute.amazonaws.com:/tmp

#. Clone the repository as usual (make sure git and git-lfs are properly installed)

#. Just push the files as usual

Keeping Production Data out of Master Branch
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Production versus test data. Any branch beginning with "production" or "application" will contain data in the "production/" subfolder. This data should stay in that branch and not be merged into master. To keep the data out, the easiest practice is to simply keep merges one-way from master into the production branch and not vice versa.

However, sometimes troubleshooting / debugging / development happens on a production branch. The cleanest way to get changes to source code or other non-production files back into master is the following.

Checkout your production branch::

  git checkout production-branch

Bring branch even with master::

  git merge master

Resolve conflicts if needed

Capture the files that are different now between production and master::

  git diff --name-only HEAD master > diff-with-master.txt

You have created a file "diff-with-master.txt" containing a listing of every file that is different.

IMPORTANT!!!! -- Edit the file diff-with-master.txt and remove all production-related data (this typically will be all files underneath "production" sub-directory.

Checkout master::

  git checkout master

Create a new branch off of master, this is where you will stage the files to then merge back into master::

  git checkout -b new-branch-with-changes-4ci

Do a file by file checkout of all differing files from production branch onto master::

  cat diff-with-master.txt | xargs git checkout production-branch --

Note, if any of our diffs include the deletion of a file on your production branch, then you will need to remove (i.e. with "git remove" these before you do the above "checkout" step and you should also remove them from the diff-with-master.txt"). If you don't do this, you will see an error message ("did not match any file(s) known to git.") and the checkout command will not be completed.

Finally, commit the files that were checked out of the production branch, push, and go create your pull request!


Automated Cloud Deployment
^^^^^^^^^^^^^^^^^^^^^^^^^^

..

    This functionality is available for core BEAM development team with Amazon Web Services access privileges. Please contact Colin_ for access to capability.

BEAM run on EC2
~~~~~~~~~~~~~~~

To run a BEAM simulation or experiment on amazon ec2, use following command with some optional parameters::

  ./gradlew deploy -P[beamConfigs | beamExperiments]=config-or-experiment-file

The command will start an ec2 instance based on the provided configurations and run all simulations in serial. At the end of each simulation/experiment, outputs are uploaded to a public Amazon S3 bucket_. To run each each simulation/experiment parallel on separate instances, set `beamBatch` to false. For customized runs, you can also use following parameters that can be specified from command line:

* **propsFile**: to specify file with default values
* **runName**: to specify instance name.
* **beamBranch**: To specify the branch for simulation, current source branch will be used as default branch.
* **beamCommit**: The commit SHA to run simulation. use `HEAD` if you want to run with latest commit, default is `HEAD`.
* **deployMode**: to specify what type of deploy it will be: config | experiment | execute
* **beamConfigs**: A comma `,` separated list of `beam.conf` files. It should be relative path under the project home. You can create branch level defaults by specifying the branch name with `.configs` suffix like `master.configs`. Branch level default will be used if `beamConfigs` is not present.
* **beamExperiments**: A comma `,` separated list of `experiment.yml` files. It should be relative path under the project home.You can create branch level defaults same as configs by specifying the branch name with `.experiments` suffix like `master.experiments`. Branch level default will be used if `beamExperiments` is not present. `beamConfigs` has priority over this, in other words, if both are provided then `beamConfigs` will be used.
* **executeClass** and **executeArgs**: to specify class and args to execute if `execute` was chosen as deploy mode
* **maxRAM**: to specify MAXRAM environment variable for simulation.
* **storageSize**: to specfy storage size of instance. May be from `64` to `256`.
* **beamBatch**: Set to `false` in case you want to run as many instances as number of config/experiment files. Default is `true`.
* **s3Backup**: to specify if copying results to s3 bucket is needed, default is `true`.
* **instanceType**: to specify s2 instance type.
* **region**: Use this parameter to select the AWS region for the run, all instances would be created in specified region. Default `region` is `us-east-2`.
* **shutdownWait**: As simulation ends, ec2 instance would automatically terminate. In case you want to use the instance, please specify the wait in minutes, default wait is 30 min.
* **shutdownBehaviour**: to specify shutdown behaviour after and of simulation. May be `stop` or `terminate`, default is `terminate`.

There is a default file to specify parameters for task: gradle.deploy.properties_ and it is advised to use it (or custom) file to specify all default values for `deploy` task and not use gradle.properties_ file because latter used as a source of default values for all gradle tasks.

The order which will be used to look for parameter values is follow:
 #. command line arguments
 #. gradle.properties_ file
 #. gradle.deploy.properties_ file or custom file specified in `propsFile`

To run a batch simulation, you can specify multiple configuration files separated by commas::

  ./gradlew deploy -PbeamConfigs=test/input/beamville/beam.conf,test/input/sf-light/sf-light.conf

Similarly for experiment batch, you can specify comma-separated experiment files::

  ./gradlew deploy -PbeamExperiments=test/input/beamville/calibration/transport-cost/experiments.yml,test/input/sf-light/calibration/transport-cost/experiments.yml

For demo and presentation material, please follow the link_ on google drive.


PILATES run on EC2
~~~~~~~~~~~~~~~~~~

It is possible to start PILATES simulation on AWS instance from gradle task  ::

  ./gradlew deployPilates [-Pparam1name=param1value [... -PparamNname=paramNvalue]]

This command will start PILATES simulation on ec2 instance with specified parameters.

* **propsFile**: to specify file with default values
* **runName**: to specify instance name.
* **startYear**: to specify start year of simulation.
* **countOfYears**: to specify count of years.
* **beamItLen**: to specify simulations year step.
* **urbansimItLen**: to specify urbansim simulation length.
* **inYearOutput**: to allow urbansim to write in year output, default is 'off'.
* **beamConfig**: to specify BEAM config file for all runs during simulation.
* **initialS3UrbansimInput**: to specify initial data for first urbansim run.
* **initialS3UrbansimOutput**: to specify initial urbansim data for first BEAM run if it is not skipped.
* **initialSkimPath**: to specify initial skim file for first urbansim run if first BEAM run is skipped. Setting this parameter to any value will lead to skipping first BEAM run.
* **s3OutputBucket**: to specify s3 output bucket name, default is `//pilates-outputs`.
* **s3OutputBasePath**: to specify s3 output path from bucket to output folder. Setting this parameter empty will lead to putting output folder in root of s3 output bucket. By default is empty.
* **pilatesScenarioName**: name of output folder. Full name will contain this parameter value and datetime of start of run. By default is `pilates`.
* **beamBranch**: to specify the branch for simulation, current source branch will be used as default branch.
* **beamCommit**: the commit SHA to run simulation. use `HEAD` if you want to run with latest commit, default is `HEAD`.
* **maxRAM**: to specify MAXRAM environment variable for simulation.
* **shutdownWait**: to specify shutdown wait after end of simulation, default is `15`.
* **shutdownBehaviour**: to specify shutdown behaviour after and of simulation. May be `stop` or `terminate`, default is `terminate`.
* **storageSize**: to specfy storage size of instance. May be from `64` to `256`.
* **region**: to specify region to deploy ec2 instance. May be different from s3 bucket instance.
* **dataRegion**: to specify region of s3 buckets. All operations with s3 buckets will be use this region. By default equal to `region`.
* **instanceType**: to specify s2 instance type.
* **pilatesImageVersion**: to specify pilates image version, default is `latest`.
* **pilatesImageName**: to specify full pilates image name, default is `beammodel/pilates`.

There is a default file to specify parameters for task: gradle.deployPILATES.properties_ and it is advised to use it (or custom) file to specify all default values for `deployPilates` task and not use gradle.properties_ file because latter used as a source of default values for all gradle tasks.

The order which will be used to look for parameter values is follow:
 #. command line arguments
 #. gradle.properties_ file
 #. gradle.deployPILATES.properties_ file or custom file specified in `propsFile`

If none of sources contains parameter, then parameter will be omitted. This will ends with output message: "`parameters wasn't specified: <omitted parameters list>`"

Running this function will leads to:
 #. creating new ec2 instance
 #. pulling from github selected branch/commit
 #. pulling from docker hub PILATES image
 #. running PILATES image with specified parameters
 #. writing output from every iteration to s3 bucket

All run parameters will be stored in `run-params` file in root of PILATES output.

Also during simulation for every BEAM run will be created a new config file with specified paths to output folder and to urbansim data.
Those config files will be created near original config file (from `beamConfig` variable) with year added to the name.
So it will be possible to rerun BEAM for selected year.


AWS EC2 start stop and terminate
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To maintain ec2 instances, there are some utility tasks that reduce operation cost tremendously.
You can start already available instances using a simple `startEC2` gradle task under aws module.
You can specify one or more instance ids by a comma saturated list as `instanceIds` argument.
Below is syntax to use the command::

  ./gradlew startEC2 -PinstanceIds=<InstanceID1>[,<InstanceID2>]

As a result of task, instance DNS would be printed on the console.


Just like starting instance, you can also stop already running instances using a simple `stopEC2` gradle task.
You can specify one or more instance ids by a comma saturated list as `instanceIds` argument.
Below is syntax to use the command::

  ./gradlew stopEC2 -PinstanceIds=<InstanceID1>[,<InstanceID2>]

It is possible not just stop instance but terminate it using `terminateEC2` gradle task.
Terminated instances are not available to start and will be completely removed along with all data they contain.
You can specify one or more instance ids by a comma saturated list as `instanceIds` argument.
Below is syntax to use the command::

  ./gradlew terminateEC2 -PinstanceIds=<InstanceID1>[,<InstanceID2>]

.. _Colin: mailto:colin.sheppard@lbl.gov
.. _bucket: https://s3.us-east-2.amazonaws.com/beam-outputs/
.. _gradle.properties: https://github.com/LBNL-UCB-STI/beam/blob/master/gradle.properties
.. _gradle.deploy.properties: https://github.com/LBNL-UCB-STI/beam/blob/master/gradle.deploy.properties
.. _gradle.deployPILATES.properties: https://github.com/LBNL-UCB-STI/beam/blob/master/gradle.deployPILATES.properties
.. _link: https://goo.gl/Db37yM


Performance Monitoring
^^^^^^^^^^^^^^^^^^^^^^

Beam uses `Kamon`_ as a performance monitoring framework. It comes with a nice API to instrument your application code for metric recoding. Kamon also provide many different pingable recorders like Log Reporter, StatsD, InfluxDB etc. You can configure your desired recorder with project configurations under Kamon/metrics section. When you start the application it will measure the instrumented components and recorder would publish either to console or specified backend where you can monitor/analyse the metrics.

If you would like to review basic JVM metrics then it is `already configured`_ so that you can use `jconsole`_.

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



.. _already configured: http://logback.qos.ch/manual/jmxConfig.html
.. _jconsole: https://docs.oracle.com/javase/8/docs/technotes/guides/management/jconsole.html
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


Instructions for forking BEAM
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
These instructions are based on `this page <https://confluence.atlassian.com/bitbucket/current-limitations-for-git-lfs-with-bitbucket-828781638.html>`_

1. Clone BEAM repo

.. code-block:: bash

    git clone https://github.com/LBNL-UCB-STI/beam

    cd beam


When asked for user name and password for LFS server (http://52.15.173.229:8080) enter anything but do not leave them blank.

2. Fetch Git LFS files

.. code-block:: bash

    git lfs fetch origin

Many tutorials on cloning Git LFS repos say one should use

.. code-block:: bash

    git lfs fetch --all origin

However, in BEAM this represents over 15 GB data and often fails.

3. Add new origin

.. code-block:: bash

    git remote add new-origin <URL to new repo>

4. Create internal master branch, master branch will used to track public repo

.. code-block:: bash

    git branch master-internal
    git checkout master-internal

5. Update .lfsconfig to have only the new LFS repo

.. code-block:: bash

    [lfs] url = <URL to new LFS repo>

Note: for Bitbucket, the <URL to new LFS repo> is <URL to new repo>/info/lfs

6. Commit changes

.. code-block:: bash

    git commit --all

7. Push to new repo

.. code-block:: bash

    git push new-origin --all

**There will be errors saying that many files are missing (LFS upload missing objects). That is OK.**

.. note:: As of this writing, the repo has around 250 MB LFS data. However, the push fails if the LFS server sets a low limit on LFS data. For example, it fails for Bitbucket free which sets a limit of 1 GB LFS data

8. Set master-internal as default branch in the repository's website.

9. Clone the new repo

.. code-block:: bash

    git clone <URL to new repo>
    cd <folder of new repo>

.. note:: Cloning might take a few minutes since the repo is quite large.

If everything turned out well, the cloning process should not ask for the credentials for BEAM's LFS server (http://52.15.173.229:8080).

10. Add public repo as upstream remote

.. code-block:: bash

   git remote add upstream https://github.com/LBNL-UCB-STI/beam


11. Set master branch to track public remote and pull latest changes

.. code-block:: bash

   git fetch upstream
   git checkout -b master upstream/master
   git pull


Scala tips
^^^^^^^^^^
Scala Collection
~~~~~~~~~~~~~~~~

Use ``mutable`` buffer instead of ``immutable var``:
****************************************************

.. code-block:: scala

   // Before
   var buffer = scala.collection.immutable.Vector.empty[Int]
   buffer = buffer :+ 1
   buffer = buffer :+ 2

   // After
   val buffer = scala.collection.mutable.ArrayBuffer.empty[Int]
   buffer += 1
   buffer += 2
   
**Additionally note that, for the best performance, use mutable inside of methods, but return an immutable**

.. code-block:: scala

   val mutableList = scala.collection.mutable.MutableList(1,2)
   mutableList += 3
   mutableList.toList // returns scala.collection.immutable.List
                      // or return mutableList but explicitly set the method return type to
                      // a common, assumed immutable one from scala.collection (more dangerous)

Don’t create temporary collections, use `view`_:
************************************************

.. code-block:: scala

   val seq: Seq[Int] = Seq(1, 2, 3, 4, 5)

   // Before
   seq.map(x => x + 2).filter(x => x % 2 == 0).sum

   // After
   seq.view.map(x => x + 2).filter(x => x % 2 == 0).sum

Don’t emulate ``collectFirst`` and ``collect``:
***********************************************

.. code-block:: scala

   // collectFirst
   // Get first number >= 4
   val seq: Seq[Int] = Seq(1, 2, 10, 20)
   val predicate: Int => Boolean = (x: Int)  => { x >= 4 }

   // Before
   seq.filter(predicate).headOption

   // After
   seq.collectFirst { case num if predicate(num) => num }

   // collect
   // Get first char of string, if it's longer than 3
   val s: Seq[String] = Seq("C#", "C++", "C", "Scala", "Haskel")
   val predicate: String => Boolean = (s: String)  => { s.size > 3 }

   // Before
   s.filter(predicate).map { s => s.head }

   // After
   s.collect { case curr if predicate(curr) => curr.head }

Prefer ``nonEmpty`` over ``size > 0``:
**************************************

.. code-block:: scala
 
  // Before
  (1 to x).size > 0
  
  // After
  (1 to x).nonEmpty
  
  // nonEmpty shortcircuits as soon as the first element is encountered

Prefer not to use ``_1, _2,...`` for ``Tuple`` to improve readability:
**********************************************************************

.. code-block:: scala

   // Get odd elements of sequence s
   val predicate: Int => Boolean = (idx: Int)  => { idx % 2 == 1 }
   val s: Seq[String] = Seq("C#", "C++", "C", "Scala", "Haskel")

   // Before
   s.zipWithIndex.collect {
       case x if predicate(x._2) => x._1   // what is _1 or _2 ??
   }

   // After
   s.zipWithIndex.collect {
       case (s, idx) if predicate(idx) => s
   }

   // Use destructuring bindings to extract values from tuple
   val tuple = ("Hello", 5)

   // Before
   val str = tuple._1
   val len = tuple._2

   // After
   val (str, len) = tuple

Great article about `Scala Collection tips and tricks`_, must read
******************************************************************

Use lazy logging
~~~~~~~~~~~~~~~~

When you log, prefer to use API which are lazy. If you use
``scala logging``, you have `it for free`_. When use ``ActorLogging`` in
Akka, you should not use `string interpolation`_, but use method with
replacement arguments:

.. code-block:: scala

   // Before
   log.debug(s"Hello: $name")

   // After
   log.debug("Hello: {}", name)

.. _view:  https://www.scala-lang.org/blog/2017/11/28/view-based-collections.html
.. _Scala Collection tips and tricks: https://pavelfatin.com/scala-collections-tips-and-tricks/#sequences-rewriting
.. _it for free: https://github.com/lightbend/scala-logging#scala-logging-
.. _string interpolation: https://docs.scala-lang.org/overviews/core/string-interpolation.html
