#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/base.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_ridehail_num_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_ridehail_num_low.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/scenarios/sfBay_ridehail_price_high.conf']"
