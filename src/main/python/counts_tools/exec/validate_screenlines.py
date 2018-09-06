import ConfigParser
import sys

import numpy as np
import pandas as pd

import utils.counts

__author__ = 'Andrew A Campbell'

'''
This script is used to compare the output of a MATSim run against a specific subset of screenlines.
'''

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    # Paths
    counts_compare_file = conf.get('Paths', 'counts_compare_file')
    screenline_file = conf.get('Paths', 'screenlines_file')
    output_file = conf.get('Paths', 'output_file')
    # Params
    aggr_hours = [int(h.strip()) for h in conf.get('Params', 'aggr_hours').split(',')]
    counts_col = conf.get('Params', 'counts_col')
    out_cols = [c.strip() for c in conf.get('Params', 'out_cols').split(',')]

    # Read the screenline file line-by-line cand calculate aggregates
    temp = []
    sl = pd.read_csv(screenline_file)
    counts_df = pd.read_csv(counts_compare_file)
    for row in sl.iterrows():
        n_ids = np.sum([row[1]['Link_ID_N_W'].isdigit(), row[1]['Link_ID_S_E'].isdigit()])
        if n_ids == 0: # no matching sensor was available for that screenline
            temp.append([row[1]['MTC Location Name'], None, None, None, None])
            continue
        else:  # one or more sensors are present
            lids = [int(id) for id in [row[1]['Link_ID_N_W'], row[1]['Link_ID_S_E']] if id.isdigit()]
        temp.append(utils.counts.validate_screenline(lids, aggr_hours, counts_df, counts_col, row[1]['MTC Location Name']))

    out_df = pd.DataFrame(data=temp, columns=out_cols)
    print 'Output df shape: %s' % str(out_df.shape)
    out_df.to_csv(output_file)






