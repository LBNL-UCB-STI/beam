
Developer's Guide
=================

.. IntelliJ IDEA Setup
   ^^^^^^^^^^

Repositories
^^^^^^^^^^^^^
The beam repository on github `is here. <https://github.com/LBNL-UCB-STI/beam>`_

The convention for merging into the master branch is that the three main model components (AgentSim, PhysSim, Router) need to be functional and the overall model must be able to run. In addition, all classes should compile and all tests should pass. The BEAM project managers will be responsible for merging between master and the three main components (see below). Pull requests will be the mechanism to notify the project managers.

The three componenet branches (agentsim, physsim, router) are for gradual development of new features. Commits to these branches should also be functional in the sense that they don't interfere with other team members working on the same component. 

+ master
    + agentsim
        + agentsim-new-feature-1 
        + agentsim-new-feature-2
    + physsim
    + router
        + router-new-feature

An example workflow for contributing a new feature to the router branch might look like this:

+ create a new branch off of the router (e.g. router-new-feature)
+ work in router-new-feature
+ get it to compile, pass tests
+ merge back into router
+ create pull request


The pev-only and pev-only-calibration branches hold a previous version of BEAM (v1.X) which is inconpatible with master but is still used for modeling and analysis work.

The following branches are deprecated and will be deleted in the near future, please do not commit to these:

  akka 
  calibration 
  development 
  sangjae 
  akka-router-parallization 


Configuration
^^^^^^^^^^^^^

We use `typesafe config <https://github.com/typesafehub/config>`_ for our configuration parser and we use the `tscfg <https://github.com/carueda/tscfg>`_ utility for generating typesafe container class for our config that we can browse with auto-complete while developing.

To use the configuration class, you need to specify two environment variables. See below for configuring these.

Then you can make a copy of the config template under::

  src/main/resources/config-template.conf

and start customizing the configurations to your use case.

To add new parameters or change the structure of the configuration class itself, simply edit the `config-template.conf` file and run the gradle task::

  gradle generateConfig

This will generate a new class `src/main/scala/beam/metasim/config/BeamConfig.scala` which will reflect the new structure and parameters.

Environment Variables
^^^^^^^^^^^^^^^^^^^^^

Depending on from where you want to run the BEAM application and from where you want to run gradle tasks, the specific place where you set these variables will differ. To run from the command line, add these statements to your .bash_profile file::

  export BEAM_SHARED_INPUTS=/path/to/beam-developers/`
  export BEAM_OUTPUTS=/path/to/your/preferred/output/destination/`

To run from IntelliJ as an "Application", edit the "Environment Variables" field in your Run Configuration to look like this::

  BEAM_OUTPUTS="/path/to/your/preferred/output/destination/";BEAM_SHARED_INPUTS="/path/to/beam-developers/"

Finally, if you want to run the gradle tasks from IntelliJ in OS X, you need to configure you variables as launch tasks by creating a plist file for each. The files should be located under :code:`~/Library/LaunchAgents/` and look like the following. Note that after creating the files you need to log out / log in to OS X and you can't Launch IntelliJ automatically on log-in because the LaunchAgents might not complete in time.

File: :code:`~/Library/LaunchAgents/setenv.BEAM_OUTPUTS.plist`::

    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
      <dict>
        <key>Label</key>
        <string>setenv.BEAM_OUTPUTS</string>
        <key>ProgramArguments</key>
        <array>
          <string>/bin/launchctl</string>
          <string>setenv</string>
          <string>BEAM_OUTPUTS</string>
          <string>/path/to/your/preferred/output/destination/</string>
        </array>
        <key>RunAtLoad</key>
        <true/>
        <key>ServiceIPC</key>
        <false/>
      </dict>
    </plist>

File: :code:`~/Library/LaunchAgents/setenv.BEAM_SHARED_INPUTS.plist`::

    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
      <dict>
        <key>Label</key>
        <string>setenv.BEAM_SHARED_INPUTS</string>
        <key>ProgramArguments</key>
        <array>
          <string>/bin/launchctl</string>
          <string>setenv</string>
          <string>BEAM_SHARED_INPUTS</string>
          <string>/path/to/beam-developers/</string>
        </array>
        <key>RunAtLoad</key>
        <true/>
        <key>ServiceIPC</key>
        <false/>
      </dict>
    </plist>

Deploying on NERSC
^^^^^^^^^^^^^^^^^^

Note, the following assumes you have configured your NERSC account to use bash as your default shell. To configure this go to the [NERSC NIM site](https://nim.nersc.gov/) and "Actions" -> "Change Shell".

Log into system::

    ssh <user>@cori.nersc.gov

Configure your bash environment if you haven't already done so. Add an environment variable to your bash profile by opening ~/.bash_profile.ext and adding these lines::

    export DIR=/project/projectdirs/m1927
    export BEAMLIB=/project/projectdirs/m1927/beam/beamlib

The first gives you a handy way to jump into our project directory (e.g. "cd $DIR") the second is what gradle will use to find the matsim static jar file. 
Go to shared project directory::

    cp /project/projectdirs/m1927/beam/

Build and deploy model if necessary (if new changes have occurred)::

    ./build-beam.sh

Submit job to batch schedule::

    cd batch
    sbatch sf-bay.sl

Monitor job::

    sqs

Setting up in Eclipse
^^^^^^^^^^^^^^^^^^^^^

Setup Matsim as a Dependency:

* Eclipse -> New Project -> Import Projects from Git
* Clone URI: git@github.com:colinsheppard/matsim.git
* Host: github.com
* Authenticate 
* Folder: e.g. C:\Users\Admin\git\matsim
* Then import just the "matsim" and "examples" subfolders as two independent projects
* Finally add "matsim-examples" as a project dependency to "matsim"

Pulling code from github:

* Eclipse -> New Project -> Import Projects from Git
* Clone URI: git@github.com:colinsheppard/beam.git 
* Host: github.com
* Authenticate 
* Folder: e.g. C:\Users\Admin\git\beam
* Import as generic project
* Add "matsim" as a project dependency to "beam"

Create gradle project:

* Import -> gradle project
* Project root: C:\Users\Admin\git\beam\
    
