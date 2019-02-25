#!/bin/bash

cd git/beam/production/application-sfbay/calibration/experiments/*/suggestions
for d in */; do
 echo ${d::-1}
 gunzip -c ${d::-1}/ITERS/it.0/0.events.xml.gz | grep ModeChoice
 #grep -Eo "mode=\"\w"  sort uniq -c;
done
