#!/bin/bash

java -jar plantuml.1.2017.18.jar -DPLANTUML_LIMIT_SIZE=8192 -o ../_static/uml/ ./*
