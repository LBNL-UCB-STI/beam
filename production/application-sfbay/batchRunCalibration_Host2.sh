#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_reduceAvailableRideHailingDrivers1pml.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_reduceAvailableRideHailingDrivers2pct.conf']"
