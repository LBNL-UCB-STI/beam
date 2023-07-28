#!/bin/bash

set -x

singularity pull --force "docker://hello-world:latest"
singularity run "hello-world_latest.sif"

set +x
