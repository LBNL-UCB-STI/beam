#!/bin/bash
export MAXRAM=240g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_ridehail_price_high.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/sfBay_ridehail_price_low.conf']"
