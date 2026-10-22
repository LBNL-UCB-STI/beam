# BEAM Release Notes
## 1.1.0
### Summary
BEAM 1.1.0 delivers major advances in multi-plan agent learning and replanning across PILATES iterations, tour-based vehicle and subtour management, freight simulation resilience, 14-pollutant EMFAC emissions modeling with multithreaded Parquet I/O, R5 routing and PhysSim/AgentSim performance, standalone and background ActivitySim/PILATES skim generation (`FullSkimsCreatorApp`), Apple Silicon (ARM64) support, and CI/CD & documentation infrastructure.

### New Features & Enhancements
#### Multi-Plan Agent Learning, Scoring & Replanning
- **Multi-Plan Memory Ingestion & Serialization (`UrbansimReaderV2` & `PlansCsvWriter`)**: Added full support for `planIndex`, `planScore`, and `planSelected` across CSV and Parquet scenario readers (`InputPlanElement`, `CsvReaders`, `ParquetReaders`, `PlanElementReader`) and `PlansCsvWriter`, enabling multi-plan agent memories to round-trip cleanly across warm starts and PILATES iterations ([#3940](https://github.com/LBNL-UCB-STI/beam/pull/3940), [#3941](https://github.com/LBNL-UCB-STI/beam/pull/3941)).
- **Multi-Plan Isolation in `HOVModeTransformer` & `PreviousRunPlanMerger`**: Keyed HOV mode transformation by `(PersonId, planIndex)` instead of `PersonId` alone, isolated unselected memory plans from the active HOV car/teleportation pool, and preserved full multi-plan sets for newly sampled agents while sorting `NaN`-scored plans last during memory trimming ([#3940](https://github.com/LBNL-UCB-STI/beam/pull/3940), [#3941](https://github.com/LBNL-UCB-STI/beam/pull/3941)).
- **In-Place Realized Leg Updates (`BeamScoringFunctionFactory`)**: Updates the selected plan's legs in-place with realized modes, departure times, travel times, and vehicle IDs upon mobsim completion (`beamRealizedUpdated = true`), preventing `ReplanningUtil.makeExperiencedMobSimCompatible` from overwriting multi-plan leg structures ([#3941](https://github.com/LBNL-UCB-STI/beam/pull/3941)).
- **Expectation-Adjusted Mode Choice Scoring (`ModeChoiceMultinomialLogit`)**: Wired `beam.replanning.subtractExpectedScores` to apply leg-level `trip_cost_dollars` (`expectedCost`) overrides and `trip_dur_min` (`travelTimeRatio`) scaling when computing all-day plan utility ([#3917](https://github.com/LBNL-UCB-STI/beam/pull/3917), [#3941](https://github.com/LBNL-UCB-STI/beam/pull/3941)).
- **Attribute Preservation During Replanning Mutation (`ReplanningUtil`)**: `copyRandomPlanAndSelectForMutation` now deep-copies leg attributes (`trip_id`, `tour_id`, `trip_dur_min`, `trip_cost_dollars`, `vehicles`) and plan attributes (`modality-style`, `scores`) onto newly cloned plans while stripping `beamRealizedUpdated` so mutated plans are realized on the next iteration ([#3944](https://github.com/LBNL-UCB-STI/beam/pull/3944)).
- **String-Typed Tour Identifiers (`Tour.tourId`)**: Converted `Tour.tourId` from `Int` to `String` to preserve alphanumeric tour IDs from ActivitySim/UrbanSim without numeric parsing failures or hash collisions ([#3941](https://github.com/LBNL-UCB-STI/beam/pull/3941)).

#### Tour Mode Choice, Subtours & En-Route Recovery
- **Subtour Vehicle Inheritance & Workplace Emergency Vehicles**: Subtours now inherit a parent tour vehicle only when the parent tour is `CAR_BASED` or `BIKE_BASED`. Walk-based parent tours holding an intermodal access/egress vehicle at a transit station hide that vehicle from workplace subtours, while allowing emergency cars issued for `CAR` subtours to be used and cleanly released back to the fleet manager upon subtour completion ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- **Intermodal Egress Retry**: Added `retryingIntermodalMode` tracking in `ChoosesMode` so agents on the final homebound leg of a `DRIVE_TRANSIT` or `BIKE_TRANSIT` tour with a station-parked vehicle retry intermodal egress routing once before abandoning the mode ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- **Mid-Tour Personal Vehicle Stranding Prevention**: When routing fails mid-tour on a `CAR_BASED` or `BIKE_BASED` tour while holding a personal vehicle, agents construct an expensive fallback vehicle trip (`createExpensiveVehicleTrip`) rather than switching to walk/transit and stranding their car or bike away from home ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- **Single-Driver Home Vehicle Retention**: Non-EV single-driver households retain their personal vehicle across back-to-back tours at home without releasing and re-requesting it from `HouseholdFleetManager`, avoiding allocation race conditions ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- **En-Route Parking Routing Failure Recovery**: Added `resumeOriginalTripAfterFailedEnrouteRouting` in `ChoosesParking` to release reserved stalls, reset en-route state, and resume the original leg when en-route routing to a parking stall returns no itinerary ([#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934)).
- **Bounded Replanning Attempts**: Increments `numberOfReplanningAttempts` in the stuck-without-car branch of `ChoosesMode`, falling back immediately to an expensive walk trip after repeated failures instead of looping indefinitely ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).

#### Freight Simulation & Commercial Vehicles
- **Freight Tour Classification & Deterministic 1:1 Vehicle Binding**: `BeamPlan` classifies tours with `PayloadWeightInKg` as `TourType.FREIGHT_TOUR`, and `HouseholdFleetManager` binds freight drivers deterministically to their assigned carrier vehicle (`whoDrivesThisFreightVehicle`) across multi-stop tours ([#3906](https://github.com/LBNL-UCB-STI/beam/pull/3906), [#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- **Mid-Tour Freight Route Failure Recovery (`gotoExpensiveFreightTrip`)**: When a freight agent encounters a mid-tour routing failure, `ChoosesMode` retains the held carrier truck and constructs a bushwhacking `CAR` fallback trip (`gotoExpensiveFreightTrip`) while preserving `FREIGHT_TOUR` mode and vehicle strategy on `_experiencedBeamPlan`, preventing truck abandonment, body-battery exhaustion, and `Wrong trip classifier WALK` exceptions ([#3906](https://github.com/LBNL-UCB-STI/beam/pull/3906), [#3943](https://github.com/LBNL-UCB-STI/beam/pull/3943)).
- **UrbanSim Parquet Vehicle Loading**: Extended `BeamVehicleUtils` and `UrbanSimScenarioLoader` with full schema inference and nullable column handling for Parquet vehicle tables ([#3930](https://github.com/LBNL-UCB-STI/beam/pull/3930), [#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934)).
- **FRISM Freight Plan Generator CLI Flags**: Added `--work-dir` and `--network-osm-pbf` CLI overrides to `src/main/python/freight/frism_to_beam_freight_plans.py` ([#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934)).

#### Emissions Modeling, Parquet Skims & LinkStats I/O
- **14-Pollutant Process-Level Emissions Pipeline**: Expanded `VehicleEmissions` and `EmissionsSkimmer` to track 14 criteria pollutants and greenhouse gases (`CH4`, `CO`, `CO2`, `HC`, `NH3`, `N2O`, `NOx`, `PM`, `PM10`, `PM25`, `ROG`, `SOx`, `TOG`, `BC`) across 10 EMFAC processes (`RUNEX`, `IDLEX`, `STREX`, `HOTSOAK`, `DIURN`, `RUNLOSS`, `PMTW`, `PMBW`, `PRDUST`, `PTOEX`) with configurable county, speed, soak-time, road-category, and fuel filters ([#3926](https://github.com/LBNL-UCB-STI/beam/pull/3926), [#3930](https://github.com/LBNL-UCB-STI/beam/pull/3930)).
- **2020–2050 Emission Rate Lookup Library & Alternative Fuel Prices**: Added scenario emission rate lookup tables spanning model years 2020–2050 across vehicle classes and powertrains, and updated benchmark fuel prices for hydrogen (`0.277` \$/MJ) and natural gas (`0.029` \$/MJ) ([#3930](https://github.com/LBNL-UCB-STI/beam/pull/3930)).
- **Multithreaded Parquet Skim Reader & Writer**: Added a bounded `ProducerConsumer` pipeline in `ParquetSkimWriter` and partitioned parallel readers in `ParquetSkimReader` (`parquetWritingChunkSize`, `parquetWritingMaxParallelism`) to read and write multi-gigabyte Parquet skims concurrently ([#3926](https://github.com/LBNL-UCB-STI/beam/pull/3926), [#3929](https://github.com/LBNL-UCB-STI/beam/pull/3929)).
- **Parquet LinkStats Output**: Added native Parquet output support for PhysSim link statistics (`beam.physsim.linkStatsOutputFileType = "parquet"`) with vehicle category breakdowns (`LinkStatsWithVehicleCategory`) ([#3917](https://github.com/LBNL-UCB-STI/beam/pull/3917)).

#### Routing, Parking & Simulation Performance
- **`ThreadLocal` R5 `StreetRouter` & McRaptor State Pooling**: Implemented `ThreadLocal` router recycling (`borrowRouter`/`returnRouter`/`withRouter`), McRaptor state pooling (`beam.routing.r5.useMcRaptorRouterPooling = true`), and configurable per-mode R5 state pool budgets (`beam.routing.r5.statePoolSize`), eliminating massive per-request R5 search-tree and state-table allocations and delivering the largest simulation runtime and GC reduction in the release ([#3921](https://github.com/LBNL-UCB-STI/beam/pull/3921), [#3926](https://github.com/LBNL-UCB-STI/beam/pull/3926)).
- **Complexity-Ordered Router Priority Queue & Dedicated Transit Thread Pool**: Replaced FIFO router actor queuing with a `PriorityQueue` ordered by routing complexity so long multi-modal transit searches cannot head-of-line block fast street queries, and added a dedicated transit routing thread pool (`beam.routing.transitRoutingThreads` / `BEAM_TRANSIT_ROUTING_THREADS`) ([#3917](https://github.com/LBNL-UCB-STI/beam/pull/3917), [#3921](https://github.com/LBNL-UCB-STI/beam/pull/3921), [#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934)).
- **R5 `v4.13.beta.4` & Drive-Transit Stop Discovery**: Upgraded R5 to `v4.13.beta.4`, enhanced `StopFindingVisitor` with post-hit search budgets at destination edge splits, added `DriveTransitFailureRecorder` diagnostics, and defaulted `beam.routing.r5.numberOfSamples = 0` for deterministic frequency-based transit schedules during simulation ([#3921](https://github.com/LBNL-UCB-STI/beam/pull/3921), [#3925](https://github.com/LBNL-UCB-STI/beam/pull/3925), [#3926](https://github.com/LBNL-UCB-STI/beam/pull/3926), [#3935](https://github.com/LBNL-UCB-STI/beam/pull/3935)).
- **Primitive 2D Travel-Time Arrays & Precomputed Road Restrictions**: Replaced map/collection travel-time lookups in `TravelTimeCalculatorHelper` and `LinkTravelTimeContainer` with flat primitive `Array[Array[Double]]` structures (`BeamTravelTime`), cached bounding-box edges in `GeoUtils`, and precomputed OSM vehicle road restrictions in `R5Wrapper` ([#3914](https://github.com/LBNL-UCB-STI/beam/pull/3914)).
- **`TollCalculator` `BitSet` Fast-Path (17–24% Morning Rush Speedup)**: Added `tolledLinkIdsBitSet` in `TollCalculator` to short-circuit untolled network links in $O(1)$ bit lookups without hash map queries ([#3936](https://github.com/LBNL-UCB-STI/beam/pull/3936)).
- **`ParkingZoneInfo` Allocation Elimination (>20% Simulation Allocation Reduction)**: Replaced Scala `Range` values in `ParkingZoneInfo.timeRestrictions` with primitive `(Int, Int)` tuples and rewrote `ParkingFunctions.canParkHere` to use a zero-allocation `while` loop, eliminating millions of boxed `Integer` allocations from `Range.hashCode` during parking search ([#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934), [#3936](https://github.com/LBNL-UCB-STI/beam/pull/3936)).
- **Quadtree Spatial Parking Search & JTS `PreparedGeometry` Caching**: Rebuilt `SearchQuadTree` / `LinkSearchQuadTree` with bounding-box pruning, `isParkableLink` filtering, configurable `searchExpansionFactor` (default `1.5`), and `linkSearchMaxCandidateScan`, plus cached JTS `PreparedGeometry` on `TAZ` for fast concentric ring containment checks ([#3917](https://github.com/LBNL-UCB-STI/beam/pull/3917), [#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934), [#3936](https://github.com/LBNL-UCB-STI/beam/pull/3936)).
- **JDEQSim Short-Link Storage Capacity Override**: Added `beam.physsim.jdeqsim.shortLink.storageCapacityOverride` to prevent artificial queue spillback and gridlock on ultra-short network links and ramps ([#3917](https://github.com/LBNL-UCB-STI/beam/pull/3917)).

#### Standalone & Background Skims (`FullSkimsCreatorApp` & ActivitySim/PILATES Integration)
- **High-Throughput Full-Matrix Skim Generation (`FullSkimsCreatorApp`)**: Up to 38x end-to-end speedup for standalone regional OD skim generation via batched worker-master Akka messaging (`BatchResponse(500)`), distance-based walk pruning, and single-hour time-invariant walk routing ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937)).
- **Multi-Modal Transit Disaggregation**: Disaggregates transit itineraries by `ActivitySimPathType` (`WLK_LOC_WLK`, `WLK_EXP_WLK`, `WLK_HVY_WLK`, `WLK_COM_WLK`, `WLK_LRF_WLK`, `DRV_*_WLK`, `WLK_*_DRV`) selecting the minimum request-to-arrival travel time per submode so all available transit submodes are populated simultaneously ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937)).
- **Dual R5 Router Support (`--directory2` / `beam.routing.r5.directory2`)**: Supports initializing a secondary R5 network directory in `ODRouterR5GHForActivitySimSkims` and `FullSkimsCreatorApp` to evaluate transit itineraries across two distinct GTFS/OSM network configurations in a single run ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937)).
- **Two-Stage Cross-Period Drive-Transit Return Trips (`--generateReturnTrips`)**: Stage 1 accumulates spatially deduplicated (<50 m) park-and-ride access station coordinates from outbound `DRV_*_WLK` trips across all time periods; Stage 2 routes return `WLK_*_DRV` trips from destinations back to the discovered parking stations ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937)).
- **Two-Pass Auto Toll Bifurcation (`--bifurcateTolls`, `--tollFilePath`)**: Evaluates bridge tolls (`BTOLL`) and value/express-lane tolls (`VTOLL`) along auto routes using `TollCalculator`. Untolled OD pairs populate both `SOV` and `SOVTOLL` in a single pass, while tolled OD pairs automatically run a second toll-avoidance pass (`avoidTolls = true`) to produce distinct toll-free (`SOV`/`HOV2`/`HOV3`) and toll-permitted (`SOVTOLL`/`HOV2TOLL`/`HOV3TOLL`) skim matrices ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937)).
- **Reproducible Screenline Toll Generator (`scripts/generate_tolls_from_screenlines.py`)**: Standalone Python utility that intersects WGS84 screenline geometries against a BEAM network (`network.csv.gz`) with bearing and road-class filters to produce link-level toll schedules ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937)).
- **Chunked Zarr & OMX Matrix Exporters with Provenance Metadata**: Added native Zarr output (`--skimsKind activitySimZarr` / `zarr`) alongside OMX, with 1-based `/lookup/zone_number` coordinate datasets, IEEE-754 float canonicalization (normalizing quiet `NaN`s and `-0.0f`), deterministic `(originIdx, destinationIdx)` ordering, and embedded cryptographic `logical_matrices_sha256` provenance attributes ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937), [#3938](https://github.com/LBNL-UCB-STI/beam/pull/3938)).
- **Zero-Copy OMX/Zarr Skim Extraction (`currentSkimView`)**: Replaced $O(N_{\text{OD}} \times |M|)$ full map snapshots (`currentSkimSnapshot`) in `ActivitySimSkimmer` with a zero-copy `currentSkimView`, reducing 2,500-OD skim export overhead to <5 ms with zero map allocations ([#3939](https://github.com/LBNL-UCB-STI/beam/pull/3939)).
- **Secondary TAZ Skim Mapping**: Added `beam.router.skim.secondary` with automatic TAZ crosswalk mapping to export skims in external regional demand model geographies ([#3917](https://github.com/LBNL-UCB-STI/beam/pull/3917)).
- **Extended Background Skims Timeout & Empty-Household Fallback**: Increased default `beam.urbansim.backgroundODSkimsCreator.calculationTimeoutHours` from 6 to 72 hours and added a fallback median household income (`50000.0`) in `ODRequester` when running standalone skims without population households ([#3939](https://github.com/LBNL-UCB-STI/beam/pull/3939)).

#### Dependencies, Security, CI/CD & Documentation
- **Apple Silicon (ARM64) Support & Native Compression JNI Upgrades**: Upgraded `snappy-java` (`1.1.10.7`), `zstd-jni` (`1.5.6-3`), and `lz4-java` (`1.8.0`) with native Darwin ARM64 and Linux AVX-512 SIMD binaries, resolving Apple M-series execution issues ([#3634](https://github.com/LBNL-UCB-STI/beam/issues/3634), [#3935](https://github.com/LBNL-UCB-STI/beam/pull/3935)).
- **Security CVE Remediation & Core Library Upgrades**: Upgraded Logback (`1.2.13`), Log4j (`2.23.1`), SnakeYAML (`1.33`), Commons Compress (`1.26.2`), Jackson (`2.12.7.1`), JUnit (`4.13.2`), FastUtil (`8.5.15`), Commons IO (`2.17.0`), Commons CSV (`1.10.0`), Typesafe Config (`1.4.3`), Scoverage (`2.0.10`), and Scala (`2.12.17`) ([#3935](https://github.com/LBNL-UCB-STI/beam/pull/3935)).
- **GitHub Actions CI Matrix & Weekly Integration Workflow**: Migrated CI from Jenkins to GitHub Actions (`.github/workflows/ci.yml`) with a `compile-and-lint` gate, a 4-shard parallel spec matrix, and a scheduled weekly integration test workflow (`.github/workflows/weekly-integration.yml`) ([#3933](https://github.com/LBNL-UCB-STI/beam/pull/3933)).
- **Sphinx Documentation & Automated GitHub Pages Deployment**: Updated user, developer, tutorial, and agent architecture guides (JDK 11, Apple Silicon Docker, `-PmaxRAM` overrides, submodules, GPLv3 copyright) and added `.github/workflows/docs.yml` to automatically build and publish documentation to [https://lbnl-ucb-sti.github.io/beam/](https://lbnl-ucb-sti.github.io/beam/) ([#3945](https://github.com/LBNL-UCB-STI/beam/pull/3945)).

### Bug fixes
- Fixed unit conversion bug in `CarWeightCalculator` where R5 free speed in kph was divided instead of multiplied by `0.621371` ([#3906](https://github.com/LBNL-UCB-STI/beam/pull/3906)).
- Fixed duplicate vehicle entries in `ChoosesMode` candidate vehicle pools and guarded `PopulationAttributes` against empty link collections ([#3906](https://github.com/LBNL-UCB-STI/beam/pull/3906)).
- Fixed Battery Electric Vehicles (BEVs) emitting non-zero evaporative/soak emissions on `EndShift` events in `VehicleEmissions` ([#3930](https://github.com/LBNL-UCB-STI/beam/pull/3930)).
- Fixed `BeamPlan.isLastElementInTour(idx)` indexing into `actsLegs` instead of `activities` ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- Fixed consecutive duplicate link IDs in MATSim driving routes (`ChoosesMode.updateMatsimPlanLegRoute`) and serialized non-network legs via `GenericRouteImpl` ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- Fixed multi-process `.lock` file cleanup on failure and added lock timeout handling in `FileUtils.readOrCreateFile` ([#3928](https://github.com/LBNL-UCB-STI/beam/pull/3928)).
- Added `duplicateDriverDiagnostic` in `HouseholdFleetManager` to report detailed person/driver/vehicle context on single-driver invariant violations ([#3934](https://github.com/LBNL-UCB-STI/beam/pull/3934)).
- Fixed `ConfigConsistencyComparator` logging false-positive missing-file warnings for `beam.agentsim.agents.freight.*` paths when `freight.enabled = false` ([#3939](https://github.com/LBNL-UCB-STI/beam/pull/3939)).
- Fixed TAZ ordering mismatch in `FullSkimsCreatorApp` to align with `tazTreeMapForASimSkimmer.orderedTazIds` and made `FullSkimsCreatorApp` exit with a non-zero status code when generation fails ([#3937](https://github.com/LBNL-UCB-STI/beam/pull/3937), [#3939](https://github.com/LBNL-UCB-STI/beam/pull/3939)).
- Fixed `UrbanSimScenarioLoader.loadScenario` returning `inputPlansCorrected = true` when `fractionOfNewPlansToUpdate <= 0` and prevented `HOVModeTransformer` from double-transforming historical resolved HOV modes ([#3940](https://github.com/LBNL-UCB-STI/beam/pull/3940)).
- Fixed `BeamPlan` normalizing `Double.NaN` scores to `null` so MATSim's `RandomUnscoredPlanSelector` recognizes unexecuted memory plans for exploration ([#3941](https://github.com/LBNL-UCB-STI/beam/pull/3941)).
- Fixed freight agents falling back to `WALK`, exhausting body energy, and throwing `Wrong trip classifier WALK` when a route fails mid-tour ([#3943](https://github.com/LBNL-UCB-STI/beam/pull/3943)).
- Fixed `ReplanningUtil.copyRandomPlanAndSelectForMutation` dropping leg and plan attributes when cloning plans for replanning mutation ([#3944](https://github.com/LBNL-UCB-STI/beam/pull/3944)).

### Known issues
- Inconsistent max memory reporting [3783](https://github.com/LBNL-UCB-STI/beam/issues/3783).
- ModalBehaviors.transitVehicleTypeVOTMultipliers does not behave as expected [3793](https://github.com/LBNL-UCB-STI/beam/issues/3793).
- Last link travel time in PathTraversal events often is equal '0' [3817](https://github.com/LBNL-UCB-STI/beam/issues/3817).
- Usage of Automated RH vehicles leads to stuckness in some cases [3788](https://github.com/LBNL-UCB-STI/beam/issues/3788).

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
