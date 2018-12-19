
## BASIC BASE PRODUCTION ON HEAD OF PROD BRANCH
gradle deploy -PbeamBranch=production-sfbay -PbeamConfigs=production/sfbay/base.conf -PbeamCommit=6f68d4eff58b1a94c52c65c35ffc8b7fa7d70d70 -PdeployMode=config -Pbatch=false -PrunName=BaseSfbay

gradle deploy -PbeamBranch=cs/pooling-production -PbeamConfigs=production/sfbay/base.conf -PbeamCommit=3a76a079a26a33c945e7c5fdaf1d0c8f9cf76fb8 -PdeployMode=config -Pbatch=false -PrunName=testPooling

gradle deploy -PbeamConfigs=production/application-sfbay/diffusion-and-rh2transit-base.conf,production/application-sfbay/diffusion-ridehail.conf,production/application-sfbay/diffusion-av.conf -PbeamCommit=2ce2248ef3f2659c12ff6788b01eee1f0f806b43 -Pbatch=false -PrunName=diffusionExperiment

gradle deploy -PbeamConfigs=production/application-sfbay/rh2transit-max.conf,production/application-sfbay/rh2transit-just-transit.conf,production/application-sfbay/rh2transit-just-rh.conf,production/application-sfbay/rh2transit-plus10.conf,production/application-sfbay/rh2transit-off.conf -PbeamCommit=3cf29f1c6fd6ca2c5bed5c9995da10e8cd92de25 -Pbatch=false -PrunName=rideHailTransitExperiment


gradle deploy -PrunName=diffusion-base-fine-tuning-rhti2-wki-6 -PbeamCommit=5cf8dfd62b50aa148c41045bb69272d13396d2a1

