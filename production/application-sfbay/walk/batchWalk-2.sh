#!/bin/bash
export MAXRAM=1920g
./gradlew run -PappArgs="['--config', 'production/application-sfbay/walk20.conf']"
./gradlew run -PappArgs="['--config', 'production/application-sfbay/walk15.conf']"
