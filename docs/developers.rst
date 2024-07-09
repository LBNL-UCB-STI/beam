
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

  src/main/resources/beam-template.conf

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

Running on IntelliJ
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The default JDK/SDK (Java Development Kit/Software Development Kit) used by IntelliJ may not work for BEAM.
If that occurs, you can configure IntelliJ to utilize the one used in your terminal (or other development tooling):

#. Go to `File > Project Structure...`
#. Select on `Project` under `Project Settings` in the side menu of the window that opened
#. Open the dropdown under `Project SDK`
#. If your expected SDK is there then select that one and hit `OK` and you are done. Otherwise,
#. If your SDK is not found, then choose `Add SDK > JDK...`
#. Browse to your JDKs home path (ie. ~/.jabba/jdk/adopt@1.11.28-0/Contents/Home)

   #. If you do not know your JDK home path then you can try executing the following in your terminal:

      * `which java` (this may be a symbolic link and not be the actual location)
      * `/usr/libexec/java_home` (or equivalent location of `java_home` for your OS)
      * `jabba which [VERSION]` if using jabba, but add `/Contents/Home` to the output
      * `sdk home java [VERSION]`
#. Once you have the HOME directory from the last step selected click the `Open` button
#. Make sure your added JDK is the selected SDK in `Project SDK` and hit OK.

Developing with Multiple Java Versions
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As BEAM now has a branch to support Java 8 along with the default of Java 11 you may find it necessary to
switch Java versions readily. In that case, you can easily do this with tooling such as
`jabba <https://github.com/shyiko/jabba>`_ or `SDKMAN <https://sdkman.io/>`_. Each allows you to download different
versions of the JDK and install them to your terminal session via commands such as
`jabba install [VERSION]` then `jabba use [VERSION]`.

Production Data And Git Submodules
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Production data is located in separate git repositories each scenario in its own repo.

Separation of production data and code is needed for:

1. Reducing the git repository size for developers
2. Easier addition or changing production data without merging back into develop code changes
3. Ability to use any production data with any code branch/commit without creation of yet another git production branch


These repositories have `beam-data-` prefix, e.g `beam-data-sfbay`

They are linked back to the parent repo by `git submodules <https://git-scm.com/book/en/v2/Git-Tools-Submodules>`_. For example sfbay is mapped to `production/sfbay`.

When you clone a parent project, by default you get the production data directories that contain submodules, but none of the files within them.
To fetch production data manually type::

   git submodule update --init --remote production/sfbay

(replace `sfbay` with other scenario if needed)

If you don't need the production data anymore and want to remove it locally you can run::

  git submodule deinit production/sfbay

or::

  git submodule deinit --all

to remove all production data.

Note that if you locally fetch the submodule then it will update the submodule pointer to the latest submodule commit.
That will result in a git change.

for example, the output of `git status` will be something like that::

  Changes not staged for commit:
    (use "git add <file>..." to update what will be committed)
    (use "git restore <file>..." to discard changes in working directory)
	  modified:   production/sfbay (new commits)

It is safe to either add this change with `git add` and commit it or drop it with `git reset`. It doesn't matter since we
always fetch the latest commit in submodule.

Using old production data
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Old production data is still available at branches `production-gemini-develop`, `inm/merge-urbansim-with-detroit` etc.

If for some reason you need to merge latest changes to these branches please note that there could be a conflict with the
same directory name for example `production/sfbay`. In that case you will need to rename this directory in production branch
to some other name before merging, commit this change and then merge the latest changes from develop.

Adding new production scenario
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

First create a new repository for the data with beam-data- prefix.

Then in the main repo type::

  git submodule add -b develop git@github.com:LBNL-UCB-STI/beam-data-city.git production/city

replacing `city` with a new scenario name, assuming that repo uses `develop` branch as default one.

Running Jupyter Notebook
~~~~~~~~~~~~~~~~~~~~~~~~

To start it on the local machine via Docker use command::

    ./gradlew jupyterStart

There are some additional parameters that can control how Jupyter is started:

* **jupyterToken**: to specify a custom token for Jupyter, if not set a random UUID will be generated as a token
* **jupyterImage**: to specify an arbitrary Jupyter docker image
* **user**: to specify a custom user for running Jupyter in Docker

Jupyter will be run in the background. To stop it use command::

   ./gradlew jupyterStop

Organizing jupyter notebooks
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

1. It is better to keep jupyter notebooks inside jupyter folder, organized by subfolders. The 'local_files' folder configured to be ignored by git.

2. In order to keep jupyter notebooks changed on AWS instance under version control one needs to download required notebooks (both .ipynb and .py files) from the instance and commit and push them locally.

3. Before pushing changed notebooks it is recommended to clear outputs, to make it easier to review and to reduce the size.

4. It is possible to mount local folders to jupyter.
One needs to copy 'jupyter/.foldersToMapInJupyter.txt' file to 'jupyter/local_files' folder and fill the file with all required folder to be mounted,
one location per row. Folders will be mounted during execution of jupyterStar gradle command.
For windows users - be sure docker is updated and configured to use linux containers.


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

    In this snippet, first two arguments are same as of `countOccurrence`. Next, it takes the actual piece of code/expression for which you want to measure the execution time/latency. In the example above we are measuring the execution time to calculate a router in `RoutingWorker`, we named the entity as `"request-router-time"` and set metric level to `Metrics.RegularLevel`. When this method executes your entity recorder record the metrics and log with provided name.

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
.. _jconsole: https://docs.oracle.com/en/java/javase/11/tools/jconsole.html
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

You can find details about scheduling a continuous integration build under DevOps section 'Configure Periodic Jobs' in the internal wiki.

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

Build BEAM docker image
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
To build Beam docker image run (in the root of Beam project)::

    $ ./gradlew -Ptag=beammodel/beam:0.9.12 buildImage

Once you have the image you can run Beam in Docker. Here is an example how to run test/input/beamville/beam.conf scenario on Windows OS::

   $ docker run -v c:/repos/beam/output:/app/output -e JAVA_OPTS='-Xmx12g' \
      beammodel/beam:0.9.12 --config test/input/beamville/beam.conf

Docker run command mounts host folder c:/repos/beam/output to be /app/output which allows to see the output of the Beam run. It also passes environment variable e JAVA_OPTS to the container in order to set maximum heap size for Java application.

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
