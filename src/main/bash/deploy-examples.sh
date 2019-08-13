
gradle deploy -PbeamConfigs=production/sfbay/base.conf -Pbatch=false -PrunName=baselineCongestionForUrbansimTeam -PdeployMode=config -PbeamBranch=production-sfbay -PbeamCommit=

gradle deploy -PbeamConfigs=production/application-sfbay/diffusion-and-rh2transit-base.conf,production/application-sfbay/diffusion-ridehail.conf,production/application-sfbay/diffusion-av.conf -PbeamCommit=2ce2248ef3f2659c12ff6788b01eee1f0f806b43 -Pbatch=false -PrunName=diffusionExperiment

gradle deploy -PbeamConfigs=production/application-sfbay/rh2transit-max.conf,production/application-sfbay/rh2transit-just-transit.conf,production/application-sfbay/rh2transit-just-rh.conf,production/application-sfbay/rh2transit-plus10.conf,production/application-sfbay/rh2transit-off.conf -PbeamCommit=3cf29f1c6fd6ca2c5bed5c9995da10e8cd92de25 -Pbatch=false -PrunName=rideHailTransitExperiment


gradle deploy -PrunName=diffusion-base-fine-tuning-rhti2-wki-6 -PbeamCommit=5cf8dfd62b50aa148c41045bb69272d13396d2a1

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti5-wki-6 -PbeamCommit=25f692c31e8340892703446a9c17391741d032b0

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti10-wki-6 -PbeamCommit=7b9219e5b0e93d322bb1944bdab29ce9afab21d1

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti15-wki-6 -PbeamCommit=541ebce2112f0b1647cb798d4a4dcece09aec927

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti2-wki-7 -PbeamCommit=eff3a4510121e12e9873b0424f6d1a27c53bd41a

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti5-wki-7 -PbeamCommit=107e598f23c2838bda93c24b716f1ea8fb0f9aee

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti10-wki-7 -PbeamCommit=a3cb8776f43e1b6dcecacc498df2f05bb29ecd95

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti15-wki-7 -PbeamCommit=bd74b4f288b262771e1dfdeedc76b76bbac9006b

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti2-wki-8 -PbeamCommit=121a272ddb29e001fcbf6ab0cc3eecf97ecfa534

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti5-wki-8 -PbeamCommit=b32c572552a2714f1f05fd2e6651a296e8aec61e

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti10-wki-8 -PbeamCommit=c11d8d931de0154b4353435127bcb10e8a5ea3dc

gradle deploy -PrunName=diffusion-base-fine-tuning-rhti15-wki-8 -PbeamCommit=423c5549d150efe7c2b72eff8c2dbe9e83a82de6
