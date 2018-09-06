import ConfigParser
from datetime import datetime
import os
import sys

import numpy as np
import pandas as pd

import utils.counts
import utils.counts_deviation

__author__ = 'Andrew A Campbell'
# This script finds the days with the greatest deviation from some reference value (such as hourly means or medians)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    # Paths
    station_TS_dir = conf.get('Paths', 'station_TS_dir')  # Path to station Time Series
    ref_counts_file = conf.get('Paths', 'ref_counts_file')
    out_file = conf.get('Paths', 'out_file')  # Where to write the counts file

    # Parameters
    start_date = conf.get('Params', 'start_date')
    end_date = conf.get('Params', 'end_date')
    days = [int(d.strip()) for d in conf.get('Params', 'days').split(',')]
    measure = conf.get('Params', 'measure')


    # Get target dates
    targ_dates = utils.counts.date_string_list(start_date, end_date, days)

    # Create the counts file
    ref = utils.counts.df_from_counts(ref_counts_file)  # DF w/ mean flow for each link
    measures = []
    keepers = []
    for i, stat in enumerate(ref.columns):
        # Get path to stat ts file
        print 'Processings station: %s' % str(stat)
        print 'Number %d of %d' % (i, ref.shape[1])
        ts_path = os.path.join(station_TS_dir, str(stat), 'time_series.csv')
        c_dev = utils.counts_deviation.CountsDeviation(ts_path, targ_dates)
        if c_dev.missing:  # if there is missing data, we skip the whole station
            print "Missing data. Skipping station: %s" % str(stat)
            continue
        c_dev.calc_measure(measure, reference=ref[stat])
        measures.append(c_dev.measures[measure])
        keepers.append(stat)
    df = pd.DataFrame(measures).transpose()
    df.columns = keepers
    df.index = targ_dates
    df.dropna(axis=1)
    df['Max_Dev'] = df.apply(np.sum, axis=1)
    df.to_csv(out_file)






