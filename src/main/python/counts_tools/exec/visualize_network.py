import ConfigParser
import time

import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from utils import spatial_tools

__author__ = "Andrew A Campbell"

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)

    # Paths
    network_shpfile = conf.get('Paths', 'network_shpfile')
    ttlistener_file = conf.get('Paths', 'ttlistener_file')

    # Params

    # Load the "raw" network into a GeoDataFrame
    raw_netwrk = gpd.read_file(network_shpfile)
    # Load the validation output
