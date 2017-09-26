#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_ridehail_price_low.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_toll_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_toll_low.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_transit_capacity_high.conf']"
