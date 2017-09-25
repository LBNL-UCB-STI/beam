#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_noIntercept.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_mode_calibration_makeCarLessAttractive.conf']"
