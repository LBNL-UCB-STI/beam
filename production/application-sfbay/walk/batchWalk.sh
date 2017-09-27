#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/walk6.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/walk10.conf']"
