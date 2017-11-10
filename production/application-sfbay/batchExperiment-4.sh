#!/bin/bash
export MAXRAM=240g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_toll_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_toll_low.conf']"
