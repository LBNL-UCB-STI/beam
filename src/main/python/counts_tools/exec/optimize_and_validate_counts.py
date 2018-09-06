import ConfigParser
from copy import deepcopy
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
    countscompare_raw_file = conf.get('Paths', 'countscompare_raw_file')
    countscompare_rescaled_file = conf.get('Paths', 'countscompare_rescaled_file')
    screenline_file = conf.get('Paths', 'screenlines_file')
    validation_file_afternoon = conf.get('Paths', 'validation_file_afternoon')
    validation_file_morning = conf.get('Paths', 'validation_file_morning')

    # Params
    morning_hours = [int(h.strip()) for h in conf.get('Params', 'morning_hours').split(',')]
    afternoon_hours = [int(h.strip()) for h in conf.get('Params', 'afternoon_hours').split(',')]
    out_cols = [c.strip() for c in conf.get('Params', 'out_cols').split(',')]

    ##
    # Step 1 - Optimize the CountsScaleFactor
    ##
    print "#################################################################################################"
    print 'Optimizing CountsScaleFactor'
    print "#################################################################################################"
    df_raw = pd.read_csv(countscompare_raw_file, sep="\t")
    # Due to an extra trailing tab in the raw file, there is an extra column we need to drop.
    try:
        df_raw.drop(['Unnamed: 5'], axis=1, inplace=True)
    except ValueError:  # in case a future version of MATSim does not have that stupid tab
        pass
    alpha, o_RMSE, r_RMSE = utils.counts.optimize_counts(df_raw)
    df_rescaled = deepcopy(df_raw)
    df_rescaled['MATSIM volumes'] = (df_raw['MATSIM volumes']*alpha).astype(int)
    df_rescaled.to_csv(countscompare_rescaled_file, index=False, sep='\t')
    print "alpha: %f, Orig RMSE: %f, Rescaled RMSE: %f" % (alpha, o_RMSE, r_RMSE)

    ##
    # Step 2 - Validate at the screenlines
    ##
    print "#################################################################################################"
    print 'Validating against screenlines'
    print "#################################################################################################"
    temp_morn = []
    temp_after = []
    sl = pd.read_csv(screenline_file)
    for row in sl.iterrows():
        # n_ids = np.sum([row[1]['Link_ID_N_W'].isdigit(), row[1]['Link_ID_S_E'].isdigit()])
        n_ids = np.sum([row[1]['Link_ID_N_W'] != 'nodata', row[1]['Link_ID_S_E'] != 'nodata'])
        if n_ids == 0:  # no matching sensor was available for that screenline
            temp_morn.append([row[1]['MTC Location Name'], None, None, None, None])
            temp_after.append([row[1]['MTC Location Name'], None, None, None, None])
            continue
        else:  # one or more sensors are present
            lids = [id for id in [row[1]['Link_ID_N_W'], row[1]['Link_ID_S_E']] if id != 'nodata']
        temp_morn.append(utils.counts.validate_screenline(lids, morning_hours, df_rescaled, 'MATSIM volumes', row[1]['MTC Location Name']))
        temp_after.append(utils.counts.validate_screenline(lids, afternoon_hours, df_rescaled, 'MATSIM volumes', row[1]['MTC Location Name']))

    # Create dfs and write validation output
    out_df_morn = pd.DataFrame(data=temp_morn, columns=out_cols)
    out_df_morn.to_csv(validation_file_morning)

    out_df_after = pd.DataFrame(data=temp_after, columns=out_cols)
    out_df_after.to_csv(validation_file_afternoon)







