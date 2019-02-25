import ConfigParser
import os
import sys

import fiona
import pandas as pd

from utils import counts

__author__ = 'Andrew A Campbell'
# This script is built to use the MTC's processed PeMS data files.
# https://mtcdrive.app.box.com/share-data

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    shape_path = conf.get('Paths', 'shape_path')
    data_path = conf.get('Paths', 'data_path')
    output_path = conf.get('Paths', 'output_path')
    x_col = conf.get('Params', 'x_col')
    y_col = conf.get('Params', 'y_col')
    epsg = conf.get('Params', 'EPSG')
    radius = float(conf.get('Params', 'radius'))
    year = int(conf.get('Params', 'year'))

    # Create the dataframe from the raw MTC file
    data_df = pd.read_csv(data_path, sep=',', usecols=['station', 'latitude', 'longitude', 'year'])
    data_df = data_df[data_df['year'] == year]  # Filter outWriter all rows not from the desired year
    data_df.drop_duplicates(inplace=True)

    # Run the link matching
    counts.match_links(data_df, shape_path=shape_path, EPSG=epsg, radius=radius,
                                           station_col='station', x_col=x_col, y_col=y_col, output_file=output_path)


