#!/bin/bash
export MAXRAM=1920g
export BEAM_OUTPUTS=/home/ubuntu/s3/
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_reduceAvailableRideHailingDrivers5pmlE.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_reduceAvailableRideHailingDrivers2pct.conf']"
