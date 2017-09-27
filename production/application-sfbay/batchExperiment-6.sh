#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_vot_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_vot_low.conf']"
