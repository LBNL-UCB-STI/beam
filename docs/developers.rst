
Developer's Guide
=================

.. IntelliJ IDEA Setup
   ^^^^^^^^^^

Repositories
^^^^^^^^^^^^^
The beam repository on github `is here. <https://github.com/LBNL-UCB-STI/beam>`_

The convention for merging into the master branch is that the three main model components (AgentSim, PhysSim, Router) need to be functional and the overall model must be able to run. In addition, all classes should compile and all tests should pass.

The three componenet branches (agentsim, physsim, router) are for gradual development of new features. Commits to these branches should also be functional in the sense that they don't interfere with other team members working on the same component.

The pev-only and pev-only-calibration branches hold a previous version of BEAM (v1.X) which is inconpatible with master but is still used for modeling and analysis work.

The following branches are deperecated and will be deleted in the near future:

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

  export BEAM_SHARED_INPUTS=/path/to/beam-developers/model-inputs/`
  export BEAM_OUTPUTS=/path/to/your/preferred/output/destination/`

To run from IntelliJ as an "Application", edit the "Environment Variables" field in your Run Configuration to look like this::

  BEAM_OUTPUTS="/path/to/your/preferred/output/destination/";BEAM_SHARED_INPUTS="/path/to/beam-developers/model-inputs/"

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
          <string>/path/to/beam-developers/model-inputs/</string>
        </array>
        <key>RunAtLoad</key>
        <true/>
        <key>ServiceIPC</key>
        <false/>
      </dict>
    </plist>
