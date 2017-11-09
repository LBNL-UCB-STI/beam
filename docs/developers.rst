
Developer's Guide
=================

.. IntelliJ IDEA Setup
   ^^^^^^^^^^

Repositories
^^^^^^^^^^^^^
The beam repository on github `is here. <https://github.com/LBNL-UCB-STI/beam>`_

The convention for merging into the master branch is that master needs to be pass all tests and at least one other active BEAM developer needs to review your changes before merging. Please do this by creating a pull request from any new feature branches into master. We also encourage you to creat pull requests early in your development cycle which gives other's an opportunity to observe and/or provide feedback in real time. When you are ready for a review, invite one or more through the pull requst. 

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

  export BEAM_OUTPUTS=/path/to/your/preferred/output/destination/`

To run from IntelliJ as an "Application", edit the "Environment Variables" field in your Run Configuration to look like this::

  BEAM_OUTPUTS="/path/to/your/preferred/output/destination/"

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
    
