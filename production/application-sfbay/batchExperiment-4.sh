#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_toll_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_toll_low.conf']"
