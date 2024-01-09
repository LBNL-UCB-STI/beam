Beam Tutorial
=============
Before starting this tutorial please make sure you have installed Beam as it is suggested by :ref:`users-guide`.
As an alternative you could use a prepared beam `docker <https://www.docker.com/>`_ image to run a scenario.
In order to be able to modify scenario config files we need to export the scenario directory to the host file system.
To do it execute the following commands in an empty directory::

    docker create --name tmp_beam beammodel/beam:0.8.6.14
    docker cp tmp_beam:/app/test ./

Urbansim SF-light Scenario
--------------------------
We are going to execute Urbansim SF-light scenario which includes 5000 households in San Francisco city. To run it use
the following command in the beam root directory::

  ./gradlew :run -PmaxRAM=10g -PappArgs="['--config', 'test/input/sf-light/sf-light-urbansim-5k-hh.conf']"

The output resides in a directory like *output/sf-light/urbansim-hh5k__2023-12-25_16-55-30_rhs*. The last part of
directory name that includes datetime would be different.

In case you are using docker you need to run the following command::

    docker run --rm -v ./output:/app/output -v ./test:/app/test -e JAVA_OPTS='-Xmx10g' beammodel/beam:0.8.6.14 --config test/input/sf-light/sf-light-urbansim-5k-hh.conf


Urbansim SF-light Scenario with mode choice in Beam
---------------------------------------------------

Urbansim scenario usually contains all the leg modes defined. That means that the agents don't make a mode choice and
just follow the assigned mode. In order to make agents do the mode choice we can clear the modes. Find
*beam.urbansim.fractionOfModesToClear* section in the file *test/input/sf-light/sf-light-urbansim-5k-hh.conf* and
set *allModes* parameter to 1.0::

    beam.urbansim.fractionOfModesToClear {
      allModes = 1.0

This means that all the modes defined in the scenario should be cleared.

You can also change the simulation name by modifying parameter *beam.agentsim.simulationName*. It allows you to clearly
distinguish the output directories because the output directory name includes the simulation name.

If you execute the above command again you can notice that now the simulation takes more time because agents need to
get all the possible travel options (routes) from their current location to the destination before
making the choice.

Changing Ride-Hail Fleet Size
-----------------------------

By changing parameter *initialization.procedural.fractionOfInitialVehicleFleet* one can change the total number of
ride-hail vehicles. The number of ride-hail vehicles calculated by multiplying this parameter value and total number of
household vehicles.
If you set it to 0.01 then you can see multiple Replanning events with the reason "ResourceUnavailable RIDE_HAIL".
Which indicates that there's not enough ride-hail vehicles.


Simulation Result Analysis
--------------------------

Beam provides multiple output files that can be analysed with data analysis tools (i.e. python `pandas
<https://pandas.pydata.org/>`_). See :ref:`model-outputs` for more details.