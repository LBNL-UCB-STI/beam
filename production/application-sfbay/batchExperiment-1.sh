#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/base.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_ridehail_num_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_ridehail_num_low.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_ridehail_price_high.conf']"
