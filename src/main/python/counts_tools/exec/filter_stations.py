import ConfigParser
from datetime import datetime
import sys
import os.path as osp
import time

import numpy as np
import pandas as pd

import utils.counts as counts
from utils.station_filter import StationFilter

__author__ = 'Andrew A Campbell'
'''
Filters outWriter PeMS stations according to data quality control heuristics.
'''

def main(args):

    ##
    # Load values from config file
    ##
    config_path = args[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)

    # Paths
    meta_path = conf.get('Paths', 'meta_path')
    stat_link_map_path = conf.get('Paths', 'stat_link_map_path')
    ts_dir = conf.get('Paths', 'ts_dir')  # Path to station Time Series
    out_cleaned_path = conf.get('Paths', 'out_cleaned_path')  # Where to write the results of filtering
    out_removed_path = conf.get('Paths', 'out_removed_path')  # Where to write the results of filtering
    out_log_path = conf.get('Paths', 'out_log_path')  # Where to write the results of filtering
    poly_path = conf.get('Paths', 'poly_path')  # Where to write the results of filtering

    # Parameters
    start_date = datetime.strptime(conf.get('Params', 'start_date'), '%Y/%m/%d')
    end_date = datetime.strptime(conf.get('Params', 'end_date'), '%Y/%m/%d')
    weekdays = [int(d) for d in [s.strip() for s in conf.get('Params', 'weekdays').split(',')]]
    counts_year = conf.get('Params', 'counts_year')

    date_list = counts.date_string_list(start_date, end_date, weekdays)

    ##
    # Initialize the StationFilter and add filters
    ##
    sf = StationFilter(ts_dir, meta_path)
    # sf.set_stations(['401620'])  # Broke __outlier_detection_SVM w/ NaN bugs


    #1 - date_range
    sf.date_range(start_date, end_date)

    #2 - link_mapping
    sf.link_mapping(stat_link_map_path)

    #3 - Missing Data
    sf.missing_data(date_list)

    #4 - boundary_buffer
    # sf.boundary_buffer(poly_path)

    #5 - outlier_detection_SVM
    # sf.outlier_detection_SVM(date_list, decision_dist=5, threshold=0.05)

    #6 - observed
    sf.observed(date_list)

    ##
    # Run the filters.
    ##
    t_start = time.time()
    sf.run_filters()
    t_end = time.time()
    print "Time to run %d filters: %d [sec]" % (len(sf.filters), t_end - t_start)
    print

    ##
    # Write the filtering results to a DataFrames
    ##
    sf.cleaned_station_ids = list(sf.cleaned_station_ids)
    sf.removed_station_ids = list(sf.removed_station_ids)

    # if len(sf.cleaned_station_ids) < len(sf.removed_station_ids):
    #     [sf.cleaned_station_ids.append(999999999) for i in
    #      np.arange(len(sf.removed_station_ids) - len(sf.cleaned_station_ids))]
    # elif len(sf.cleaned_station_ids) > len(sf.removed_station_ids):
    #     [sf.removed_station_ids.append(999999999) for i in
    #      np.arange(len(sf.cleaned_station_ids) - len(sf.removed_station_ids))]

    print "cleaned_station_ids"
    print sf.cleaned_station_ids
    print
    print "removed_station_ids"
    print sf.removed_station_ids
    print
    # print "removed station reasons"
    # with open(out_log_path, 'w') as fo:
    #     for stat in sf.removed_stats_reasons.items():
    #         print stat
    #         fo.write(str(stat) + '\n')

    # df_out = pd.DataFrame({'cleaned': sf.cleaned_station_ids, 'removed': sf.removed_station_ids})
    # df_out.to_csv(out_df_path, index=False)  # Write the results to a csv
    sf.write_cleaned_stations(out_cleaned_path)
    sf.write_removed_stations(out_removed_path)
    sf.write_removed_reasons_log(out_log_path)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    main(sys.argv)


