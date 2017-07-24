#!/bin/bash

#java -cp . -Xmx30g -XX:+HeapDumpOnOutOfMemoryError -jar build/libs/beam.jar "$BEAM_SHARED_INPUTS/../beam-core/" "model-inputs/calibration-v2/config-10k.xml" "$BEAM_OUTPUTS" 
#java -cp . -Xmx30g -XX:+HeapDumpOnOutOfMemoryError -jar build/libs/beam.jar "$BEAM_SHARED_INPUTS/../beam-core/" "model-inputs/calibration-v2/config-68k.xml" "$BEAM_OUTPUTS" 
java -cp . -Xmx30g -XX:+HeapDumpOnOutOfMemoryError -jar build/libs/beam.jar "$BEAM_SHARED_INPUTS/../beam-core/" "model-inputs/calibration-v2/config-bigger-batteries.xml" "$BEAM_OUTPUTS" 
