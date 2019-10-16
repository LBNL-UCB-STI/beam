# skipping initial beam run and specifying initial skim file
gradle deployPilates -PrunName=inm/testRunPilates -PstartYear=2010 -PcountOfYears=15 -PbeamItLen=5 -PurbansimItLen=5 -PbeamBranch=production-sfbay-develop -PbeamConfigs=production/sfbay/smart/smart-baseline-pilates-small-test.conf -PinitialS3UrbansimInput=//inm-test-run-pilates/initial_input/ -PpilatesScenarioName=pilatesScenarioName -PinitialSkimsPath=//inm-test-run-pilates/pilates-outputs/pilatesScenarioName1_/2015/beam/sfbay-smart-base-pilates-small__2019-10-08_20-54-59/ITERS/it.0/0.skims.csv.gz

# with initial beam run
gradle deployPilates -PrunName=inm/testRunPilates -PstartYear=2010 -PcountOfYears=15 -PbeamItLen=5 -PurbansimItLen=5 -PbeamBranch=production-sfbay-develop -PbeamConfigs=production/sfbay/smart/smart-baseline-pilates-small-test.conf -PinitialS3UrbansimInput=//inm-test-run-pilates/initial_input/ -PpilatesScenarioName=pilatesScenarioName -PinitialS3UrbansimOutput=//inm-test-run-pilates/initial_output/
