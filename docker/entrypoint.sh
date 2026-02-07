#!/usr/bin/env bash

# Support running different main classes via BEAM_MAIN_CLASS env var
# Default: beam.sim.RunBeam (standard BEAM simulation)
# For skims: scripts.BackgroundSkimsCreatorApp
MAIN_CLASS="${BEAM_MAIN_CLASS:-beam.sim.RunBeam}"

java $JAVA_OPTS -cp /app/resources:/app/classes:/app/libs/* "$MAIN_CLASS" "$@"