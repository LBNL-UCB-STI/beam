#!/bin/bash

input_osm="/Users/daddy30000/14_Mobility_Sim/GoogleDrive/beam/my-developers/model-inputs/r5/bay_area_all_roads.osm.pbf"
inpiut_poly="/Users/daddy30000/14_Mobility_Sim/GoogleDrive/beam/sf-light_v2/shape/sflight_muni_mask_0.poly"
output_osm="/Users/daddy30000/14_Mobility_Sim/GoogleDrive/beam/sf-light_v2/shape/sfligh_muni.osm"

sh osmosis --rb file=$input_osm --bounding-polygon file=$inpiut_poly --wx file=$output_osm