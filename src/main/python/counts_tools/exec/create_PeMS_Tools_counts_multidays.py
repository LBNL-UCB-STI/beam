import ConfigParser
from datetime import datetime
import sys

import utils.counts

__author__ = 'Andrew A Campbell'
# This script creates MATSim validation counts for a range of dates and specific weekdays

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = str(sys.argv[1])
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)

    # Paths
    station_TS_dir = conf.get('Paths', 'station_TS_dir')  # Path to station Time Series
    stat_link_map_file = conf.get('Paths', 'stat_link_map_file')  # Template xml doc for building counts
    out_file = conf.get('Paths', 'out_file')  # Where to write the counts file
    filter_list_path = conf.get('Paths', 'filter_list_path')

    # Parameters
    start_date = datetime.strptime(conf.get('Params', 'start_date'), '%Y/%m/%d')
    end_date = datetime.strptime(conf.get('Params', 'end_date'), '%Y/%m/%d')
    weekdays = [int(d) for d in [s.strip() for s in conf.get('Params', 'weekdays').split(',')]]
    counts_name = conf.get('Params', 'counts_name')
    counts_desc = conf.get('Params', 'counts_desc')
    counts_year = conf.get('Params', 'counts_year')

    # Create the date_list
    date_list = utils.counts.date_string_list(start_date, end_date, weekdays)

    # Get the filtered stations list
    filter_list = []
    with open(filter_list_path, 'r') as fi:
        fi.next()  # burn header
        for line in fi:
            filter_list.append(line.split(',')[0])

    # Create the counts file
    utils.counts.create_PeMS_Tools_counts_multiday(station_TS_dir,
                                                   stat_link_map_file,
                                                   date_list,
                                                   counts_name,
                                                   counts_desc,
                                                   counts_year,
                                                   filter_list = filter_list,
                                                   out_file=out_file,printerval=1)

