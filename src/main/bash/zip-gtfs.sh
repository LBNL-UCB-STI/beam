#!/usr/bin/env bash

for i in *;do echo "$i"; cd "$i"; zip "$i.gtfs.zip" *; cd ..; done
