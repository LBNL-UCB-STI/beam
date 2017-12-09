#!/usr/bin/env bash
osmosis --read-pbf file=~/Downloads/ohio-latest.osm.pbf --bounding-box top=40.532006 left=-83.715868 bottom=39.323214 right=-82.160572 completeWays=yes --tf reject-ways highway=service --write-pbf file=columbus.osm.pbf
osmosis --read-pbf file=~/Documents/beam/input/california-latest.osm.pbf --bounding-box top=37.7256 left=-123.4232 bottom=36.9369 right=-121.6254 completeWays=yes --tf reject-ways highway=service --write-pbf file=sf-bay-noservice.osm.pbf

osmosis --read-pbf file=~/Documents/beam/input/california-latest.osm.pbf --bounding-box top=37.7256 left=-123.4232 bottom=36.9369 right=-121.6254 completeWays=yes completeRelations=yes clipIncompleteEntities=true --write-pbf file=sf-bay.osm.pbf
