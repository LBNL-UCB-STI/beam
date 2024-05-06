# BEAM Release Notes
## 1.0.0
### New Features
#### Ride-hail features
- DemandFollowingRepositionig: Reposition to the random activity's location.
- Inverse square demand following repositioning.
- Ride Hail transit using pooled legs.
- Multiple rid-hail managers.
- Pickups and dropoffs affect link travel time.
- Additional parameters for links.
- Restrict road access by free speed.
- Google Map API integration.
- Wheelchair accessible ride-hail vehicles.
- Ride-Hail CAVs charging.
- Introduced ride-hail stops.
- Ride-hail name in OD skims.
- Configuring allowed ride-hail modes.
- RideHail skimmer.

#### Mode choice feature
- Allowing to do a mode choice if the current mode cannot be implemented.
- Improved tour mode choice.

#### Router features
- R5: Limit routing by max distance for BIKE.
- Introduced GraphHopper router.
- Not found routes metric.
- Introduced Native CCH router.
- Send routing failures to skims.

#### Parking features
- Hierarchical parking manager to improve parking stall search performance.
- Link based parking sampling.
- Parking Manager on the link level.
- Improved performance of parking and charging manager.
- Assign parking stalls to households.
- Parking skimmer.
- Include parking in activity generation.
- New parking stall search algorithm.

#### Freight features
- Freight simulation.
- Freight origin-destination skims.
- Vehicle energy rate based on the weight of the truck + payload.

#### Physsim
- Possibility to write PhysSim events to CSV.
- PhysSim XML to OSM converter.
- New PhysSim mode: BPR.
- Performance: Parallel Event Manager in PhysSim.
- Added option to convert PhysSim network to PBF directly.
- Artificial increasing of traffic flow for better PhysSim.
- Allow choosing an event manager for PhysSim.
#### Scripts / Postprocessing:
- Introduced Jupyter notebooks in Beam source code.
- Jupyter notebook for map analysis of osm.pbf and PhysSim network.
- Jupyter notebook for linkstats and network.csv files analysis.
- Jupyter notebook to adjust GTFS time and timezone and to downsample scenarios based on a shape file.
- Output analysis with python scripts.
- A script for filtering points from shapefile.
- Beam Health Analysis script.
- Frequency converter utility
- Introduced a possibility to execute a Jupyter server along with Beam.
- Added various graphs/statistics.
- A tool to get a shape file out of stops coordinates from GTFS archive.
- Matsim network to shape file converter.
- Travel time & distance calculator app.
- Util to compare values of two config files.
- Utility to create TAZ geofences out of shapefiles.
- Possibility to read a shape file as TAZ file.
#### Modes
- Mode BIKE_TRANSIT is added.
- Special treatment of bike lanes.
- Simulating preference to the transit routes with lower crowdedness.
- Shared vehicles on route egress.
- HOV2 and HOV3 modes from activitysim plans.
- Shared bike repositioning.
- Include intercepts in mode utilities.
- Train station car usage.
- Micromobility modeling.
- Add buffer time for access to transit.
- Allow emergency vehicles for HOV modes.
- Create household cars from Vehicle types.
- Added trip id to transitVehicleTypesByRoute File.
#### Skims
- Generate skims for provided origin-destination pairs and modes.
- Producing activitysim skims at runtime.
- Keep track of wait times in skims.
- More skimmer events.
- Use TAZ geometries for skim origin/destination.
- Throw skimmer events for non-chosen itineraries.
- Use non-generalized times and costs in asim skimmer.
- Activitysim skims in omx format.
#### Electric Vehicles
- Charging compatibility is taken into consideration.
- Co-simulation of power providing/consuming with HELICS lib.
- In-simulation charging scale up.
- Initial State of Charge for beam vehicles.
- En-route charging.
#### Refactoring
- Removes deprecated parameters from configurations.
- Documentation improvements.
- Input Consistency Check.
#### Activities / Plans
- Generate secondary activities.
- Remove activities/persons for certain industries.
- Allow to merge the urbansim input plans with the last BEAM run output plans.
- Fixed activity duration based on the activity name.
- Create separate plans based on planIndex and set "selected" only (valid) one of them.
- Create and store a network route for a personal plan if it is empty.
- Force vehicle creation when plans require it.
#### Debugging / Logging
- Improved simulation got stuck detection.
- Storing the scheduler state in case of Beam exists with an error.
- Beam internal message logging.
- Health metrics analysis in beam output directory.
#### UrbanSim
- Urbansim mode mapping from config.
- Include urbansim trip_id as an attribute in beam plans.
- Get leg mode directly from Urbansim plans.
- Configurable timeout for urbansim scenario loading.
#### Deployment
- Add a possibility to automatically run Beam on Lawrencium HPC.
- Add a possibility to automatically run Beam on Google Compute Engine.
- Deployment spreadsheet improvements.

### Bug fixes
- Make BeamVehicle thread safe.
- Use only activities from the plan elements.
- Fixed issue in secondary activities.
- Warm start fix.
- Fix reading of OSM files generated from XML.
- Fixed shared vehicle parking issues.
- Gradle execute task fix.
- Fix the ErrorListener to actually handle a missed interrupt.
- Fix RouteDumper.
- Fix r5 issue: No valid itineraries found for path computed in RaptorWorker.
- Fixes for parking stall sampling with high availability.
- Fix log4j issue.
- Route dumper fix.
- Populate riders for PathTraversal events for Bike and Walk modes.
- Fix protocols to release vehicles properly before starting a trip.
- Fix for displaying long legend text for NonArrivedAgentsAtTheEndOfSimulation.
- Fix Beam doesn't stop when an exception occurs.
- Fix AWS deployment issues.
- Fix skimTypes for skimmers.
- Fix issue of duplicate column names in passengerPerTripSubway.csv when the data contains only zeros.
- Do not generate duplicate values for WALK skims.
- Fixed lots of similar log messages.
- Fix the boxing issue.
- Don't fail if we're missing a vehicle's fuel type.
- Fixed Memory Leak that caused keeping EventsManagerImpl for the whole run.
- Fix of not using parking file of the tail of ride-hail managers.
- Fixed misuse of withWheelchair param.
- Fix: ride hail transit re-enable.
- Get RIDE_HAIL_TRANSIT mode works with RH stops enabled.
- Fix to EntitiesTransformStrategy.

### Known issues

- Inconsistent max memory reporting [3783](https://github.com/LBNL-UCB-STI/beam/issues/3783).
- NullPointerException in org.matsim.core.population.routes.RouteUtils.calcDistance [3824](https://github.com/LBNL-UCB-STI/beam/issues/3824).
- ModalBehaviors.transitVehicleTypeVOTMultipliers does not behave as expected [3793](https://github.com/LBNL-UCB-STI/beam/issues/3793).
- Last link travel time in PathTraversal events often is equal '0' [3817](https://github.com/LBNL-UCB-STI/beam/issues/3817).
- compileScoverageScala gradle task doesn't work [3808](https://github.com/LBNL-UCB-STI/beam/issues/3808).
- Usage of Automated RH vehicles leads to stuckness in some cases [3788](https://github.com/LBNL-UCB-STI/beam/issues/3788).
- BEAM cannot be run on Apple ARM arch (Apple M1 chip) [3634](https://github.com/LBNL-UCB-STI/beam/issues/3634).
- Unnecessary warnings in the log [3864](https://github.com/LBNL-UCB-STI/beam/issues/3864).
