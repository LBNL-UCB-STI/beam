#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_reduceAvailableRideHailingDrivers5pmlC.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_reduceAvailableRideHailingDrivers5pct.conf']"
