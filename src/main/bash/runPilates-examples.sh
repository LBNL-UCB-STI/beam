# used as instance name only
runName=inm/rw/pilatesTest

# pilates simulation years: start, count, delta and urbansim parameter
startYear=2010
countOfYears=30
beamItLen=5
urbansimItLen=5

# beam config used to start BEAM
beamConfig=production/sfbay/smart/smart-baseline-pilates-0it.conf

# initial data for first urbansim run.
# urbansim require those data and skims file from BEAM
initialS3UrbansimInput=//pilates-initial-data/27thAug2019/initialUrbansimInput

# initial urbansim data for first BEAM run if not skipped
initialS3UrbansimOutput=//pilates-initial-data/27thAug2019/initialBeamInput

# initial skim file path. setting this leads to skip first BEAM run
initialSkimPath=""

# s3 output bucket name
s3OutputBucket=//inm-test-run-pilates

# base output path, needed if one want to place output not in root of s3 bucket
s3OutputBasePath=/pilates-outputs

# output folder name. full name contains this parameter and datetime of start of run
pilatesScenarioName=smartBaselinePilatesOneIteration

# used to setup MAXRAM environment variable
maxRAM=350g

# git parameters. commit by default is HEAD
beamBranch=inm/production-sfbay-develop
beamCommit='HEAD'

# shutdown parameters
shutdownWait=15
shutdownBehaviour=stop

# instance configuration
storageSize=256
region=us-east-2
instanceType=m5d.24xlarge

echo "gradle deployPilates \
-PrunName=$runName -PpilatesScenarioName=$pilatesScenarioName \
-PbeamBranch=$beamBranch -PbeamCommit=$beamCommit \
-PstartYear=$startYear -PcountOfYears=$countOfYears -PbeamItLen=$beamItLen -PurbansimItLen=$urbansimItLen \
-PbeamConfig=$beamConfig \
-PinitialS3UrbansimInput=$initialS3UrbansimInput -PinitialS3UrbansimOutput=$initialS3UrbansimOutput \
-PinitialSkimPath=$initialSkimPath \
-Ps3OutputBucket=$s3OutputBucket -Ps3OutputBasePath=$s3OutputBasePath \
-PmaxRAM=$maxRAM \
-PshutdownWait=$shutdownWait -PshutdownBehaviour=$shutdownBehaviour \
-PstorageSize=$storageSize -Pregion=$region -PinstanceType=$instanceType"