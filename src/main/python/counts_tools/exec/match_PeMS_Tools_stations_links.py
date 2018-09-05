import ConfigParser
import os
import sys

import fiona
import pandas as pd

from utils import counts

__author__ = 'Andrew A Campbell'
# This script reads a PeMS station 5-minute metadata file and matches the stations to links in a MATSim
# network.

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    shape_path = conf.get('Paths', 'shape_path')
    meta_path = conf.get('Paths', 'meta_path')
    output_path = conf.get('Paths', 'output_path')
    x_col = conf.get('Params', 'x_col')
    y_col = conf.get('Params', 'y_col')
    epsg = conf.get('Params', 'EPSG')
    radius = float(conf.get('Params', 'radius'))
    link_regex = conf.get('Params', 'link_regex')


    # Run the link matching
    counts.match_links_from_metadata(meta_path, shape_path=shape_path, EPSG=epsg, radius=radius, station_col='ID',
                                     x_col=x_col, y_col=y_col, output_file=output_path, link_regex=link_regex)


