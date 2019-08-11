

gradle deploy -PbeamConfigs=production/application-sfbay/rh2transit-max.conf,production/application-sfbay/rh2transit-just-transit.conf,production/application-sfbay/rh2transit-just-rh.conf,production/application-sfbay/rh2transit-plus10.conf,production/application-sfbay/rh2transit-off.conf -PbeamCommit=3cf29f1c6fd6ca2c5bed5c9995da10e8cd92de25 -Pbatch=false -PrunName=rideHailTransitExperiment

 gradle stopEC2 -Pregion=us-east-2 -Pcommand=terminate -PinstanceIds=i-0030c522aecd6b19e

gradle deploy -PbeamConfigs=production/sfbay/base.conf -Pbatch=false -PdeployMode=config -PbeamBranch=production-sfbay -PrunName=  -PbeamCommit=
