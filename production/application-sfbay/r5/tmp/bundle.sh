#!/bin/bash

for D in `find . ! -path . -type d`
do
    cd $D
    zip "$D.zip" *
    mv "$D.zip" ../
    cd ..
done
