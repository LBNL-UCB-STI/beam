#!/bin/bash
export MAXRAM=240g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_transit_capacity_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_transit_capacity_low.conf']"
