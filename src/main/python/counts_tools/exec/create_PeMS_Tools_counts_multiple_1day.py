import ConfigParser
from datetime import datetime
from os import path
import sys

import utils.counts

__author__ = 'Andrew A Campbell'
# This script creates MATSim validation counts for a single day based on the output of PeMS_Tools.

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    # Paths
    station_TS_dir = conf.get('Paths', 'station_TS_dir')  # Path to station Time Series
    stat_link_map_file = conf.get('Paths', 'stat_link_map_file')  # Template xml doc for building counts
    out_root = conf.get('Paths', 'out_root')  # Where to write the counts file
    filter_list_path = conf.get('Paths', 'filter_list_path')

    # Parameters
    start_date = datetime.strptime(conf.get('Params', 'start_date'), '%m/%d/%Y')
    end_date = datetime.strptime(conf.get('Params', 'end_date'), '%m/%d/%Y')
    counts_year = conf.get('Params', 'counts_year')

    # Get the filtered stations list
    filter_list = []
    with open(filter_list_path, 'r') as fi:
        fi.next()  # burn header
        for line in fi:
            filter_list.append(line.split(',')[0])

    # Create the date_list
    weekdays = range(7)  # use all days of the week
    date_list = utils.counts.date_string_list(start_date, end_date, weekdays)

    # Create the counts file
    # utils.counts.create_PeMS_Tools_counts_singleday(station_TS_dir, stat_link_map_file,
    #                                                 day_date,
    #                                                 counts_name,
    #                                                 counts_desc,
    #                                                 counts_year,
    #                                                 out_file=out_file)

    for date in date_list:
        print "Processing date: %s" % date
        day_date_list = [date]
        dparts = date.split('/')
        out_name = "%s_%s_%s_counts.xml" % (dparts[2], dparts[0], dparts[1])
        out_file = path.join(out_root, out_name)
        counts_name = out_name
        counts_desc = date
        utils.counts.create_PeMS_Tools_counts_multiday(station_TS_dir,
                                                   stat_link_map_file,
                                                   day_date_list,
                                                   counts_name,
                                                   counts_desc,
                                                   counts_year,
                                                   filter_list=filter_list,
                                                   out_file=out_file);

