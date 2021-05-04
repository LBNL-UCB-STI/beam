#!/bin/bash

java -jar plantuml.1.2021.5.jar -DPLANTUML_LIMIT_SIZE=16384 -o ../_static/uml/ ./*
