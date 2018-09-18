#!/bin/bash

echo `whoami`
#module load java/jdk1.8.0_51
#cassandra -f
cd /home/ubuntu/git/beam/build/libs/
java -Xmx238g -XX:+HeapDumpOnOutOfMemoryError -jar beam.jar "/home/ubuntu/pev-only/" "model-inputs/calibration-v2/config-bigger-batteries-1.5x-morework100.xml" "/home/ubuntu/pev-only/output/" &> /home/ubuntu/pev-only/run.log

