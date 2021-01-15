#!/usr/bin/env bash

java $JAVA_OPTS -cp /app/resources:/app/classes:/app/libs/* beam.sim.RunBeam "$@"