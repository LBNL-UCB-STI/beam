#!/usr/bin/env python
# coding: utf-8

# In[1]:


#1) Create a summary table of more scenarios
#2) Create a summary of the summary table


# In[2]:


get_ipython().system(' pip install geopandas')
get_ipython().system(' pip install pandas')
get_ipython().system(' pip install pygeos')
get_ipython().system(' pip install boto')
get_ipython().system(' pip install s3fs')
get_ipython().system(' pip install shapely')

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import gzip
import time
from itertools import groupby


# In[3]:


#Inputs filepath, data_names, names

nrows = None #None for all rows



#######################################################################################################################
#############NYC##############
fp = "s3://beam-outputs/output/newyork/"
output_nm = 'SummaryTable_NYC.csv'
len_id_transit = 10
fp_res = 'outputs/'
is_NYC = True
is_WC = False
data_names = [
              # 'new-york-split_scenario-part-1__2022-06-03_17-21-39_iqs/ITERS/it.0/0.events.csv.gz', 
              # 'new-york-split_scenario-part-2__2022-06-15_14-26-18_cuu/ITERS/it.0/0.events.csv.gz', 
              # 'new-york-split_scenario-part-3__2022-06-15_14-26-18_dmi/ITERS/it.0/0.events.csv.gz',
              'new-york-300k-calibration-7__2022-06-25_17-40-03_aui/ITERS/it.10/10.events.csv.gz',
              # 'new-york-300k-calibration-11__2022-06-28_21-55-14_hbf/ITERS/it.10/10.events.csv.gz',
              # 'new-york-300k-calibration-12__2022-06-28_21-54-34_tav/ITERS/it.10/10.events.csv.gz',
              # 'new-york-300k-calibration-13__2022-06-29_17-21-34_psu/ITERS/it.10/10.events.csv.gz',
              # 'new-york-300k-calibration-15__2022-06-30_15-57-54_zqu/ITERS/it.10/10.events.csv.gz',
              # 'new-york-300k-calibration-16__2022-07-04_14-46-10_wvt/ITERS/it.10/10.events.csv.gz',
              # 'new-york-300k-calibration-17__2022-07-05_20-44-02_ccs/ITERS/it.10/10.events.csv.gz',
              # 'new-york-450k-calibration-18__2022-07-08_15-39-57_pxy/ITERS/it.10/10.events.csv.gz',
              # 'new-york-450k-calibration-19__2022-07-12_17-14-44_esm/ITERS/it.10/10.events.csv.gz',
              # 'new-york-450k-calibration-20__2022-07-12_22-31-28_czx/ITERS/it.10/10.events.csv.gz',
              # 'new-york-baseline-0-of-10__2022-07-17_01-19-13_fgz/ITERS/it.5/5.events.csv.gz',
              # 'new-york-baseline-3-of-10__2022-07-17_01-19-21_soy/ITERS/it.5/5.events.csv.gz',
              # 'new-york-baseline-4-of-10__2022-07-17_01-19-20_qig/ITERS/it.5/5.events.csv.gz',
              # 'new-york-baseline-5-of-10__2022-07-17_01-19-12_yyt/ITERS/it.5/5.events.csv.gz',
              # 'new-york-baseline-6-of-10__2022-07-17_01-19-12_dgi/ITERS/it.5/5.events.csv.gz',
              # 'new-york-baseline-7-of-10__2022-07-17_01-19-14_qip/ITERS/it.5/5.events.csv.gz',
              # 'new-york-baseline-8-of-10__2022-07-17_01-19-11_oko/ITERS/it.5/5.events.csv.gz'
#               'new-york-baseline-2-of-10__2022-07-19_01-38-47_ryr/ITERS/it.0/0.events.csv.gz',
#               'new-york-baseline-9-of-10__2022-07-19_01-38-46_hfm/ITERS/it.0/0.events.csv.gz',
#               'new-york-baseline-1-of-10__2022-07-19_15-06-02_ewc/ITERS/it.0/0.events.csv.gz'
            ]

plan_names = [
              # 'new-york-split_scenario-part-1__2022-06-03_17-21-39_iqs/ITERS/it.0/0.plans.csv.gz', 
              # 'new-york-split_scenario-part-2__2022-06-15_14-26-18_cuu/ITERS/it.0/0.plans.csv.gz', 
              # 'new-york-split_scenario-part-3__2022-06-15_14-26-18_dmi/ITERS/it.0/0.plans.csv.gz',
              'new-york-300k-calibration-7__2022-06-25_17-40-03_aui/ITERS/it.10/10.plans.csv.gz',
              'new-york-300k-calibration-11__2022-06-28_21-55-14_hbf/ITERS/it.10/10.plans.csv.gz', 
              # 'new-york-300k-calibration-12__2022-06-28_21-54-34_tav/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-300k-calibration-13__2022-06-29_17-21-34_psu/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-300k-calibration-15__2022-06-30_15-57-54_zqu/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-300k-calibration-16__2022-07-04_14-46-10_wvt/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-300k-calibration-17__2022-07-05_20-44-02_ccs/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-450k-calibration-18__2022-07-08_15-39-57_pxy/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-450k-calibration-19__2022-07-12_17-14-44_esm/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-450k-calibration-20__2022-07-12_22-31-28_czx/ITERS/it.10/10.plans.csv.gz',
              # 'new-york-baseline-0-of-10__2022-07-17_01-19-13_fgz/ITERS/it.5/5.plans.csv.gz',
              # 'new-york-baseline-3-of-10__2022-07-17_01-19-21_soy/ITERS/it.5/5.plans.csv.gz',
              # 'new-york-baseline-4-of-10__2022-07-17_01-19-20_qig/ITERS/it.5/5.plans.csv.gz',
              # 'new-york-baseline-5-of-10__2022-07-17_01-19-12_yyt/ITERS/it.5/5.plans.csv.gz',
              # 'new-york-baseline-6-of-10__2022-07-17_01-19-12_dgi/ITERS/it.5/5.plans.csv.gz',
              # 'new-york-baseline-7-of-10__2022-07-17_01-19-14_qip/ITERS/it.5/5.plans.csv.gz',
              # 'new-york-baseline-8-of-10__2022-07-17_01-19-11_oko/ITERS/it.5/5.plans.csv.gz'
#               'new-york-baseline-2-of-10__2022-07-17_01-19-11_oko/ITERS/it.5/5.plans.csv.gz',
#               'new-york-baseline-9-of-10__2022-07-17_01-19-11_oko/ITERS/it.5/5.plans.csv.gz',
#               'new-york-baseline-1-of-10__2022-07-17_01-19-11_oko/ITERS/it.5/5.plans.csv.gz'
                ]

names = [
    # 'Baseline Part1',
         # 'Baseline Part2',
         # 'Baseline Part3',
         'Run7',
         'Run11',
         # 'Run12',
         # 'Run13',
         # 'Run15',
         # 'Run16',
         # 'Run17',
         # 'Run18',
         # 'Run19',
         # 'Run20',
         # 'Run21-0/10-fullpop', 
         # 'Run21-3/10-fullpop', 
         # 'Run21-4/10-fullpop', 
         # 'Run21-5/10-fullpop', 
         # 'Run21-6/10-fullpop', 
         # 'Run21-7/10-fullpop', 
         # 'Run21-8/10-fullpop',
        #  'Run21-2/10-fullpop', 
        #  'Run21-9/10-fullpop', 
        #  'Run21-1/10-fullpop', 
        ]

#######################################################################################################################
# fp = "s3://beam-outputs/"
# output_nm = 'SummaryTable_Pilates_invrepo_disabilities.csv'
# len_id_transit = 2
# fp_res = 'outputs/'
# is_NYC = False
# is_WC = True
# data_names = [
#                 'pilates-outputs/sfbay-base-20220409/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_0.125-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_0.25-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_0.5-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_1.75-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-transit_frequencies_0.5-20220528/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-transit_frequencies_1.5-20220529/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-transit_frequencies_2.0-20220529/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_0.0-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_0.5-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_1.5-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_3.0-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_0.2-20220610/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_0.5-20220610/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_1.5-20220614/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_5.0-20220616/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_0.2-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_0.5-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_0.8-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_1.6-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_0.0-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_0.5-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_0.75-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_1.5-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-pilates_june_test-20220616/beam/year-2010-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_price_0.5-20220616/beam/year-2010-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-1T-20220721/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-1T-SF-VT-20220722/beam/year-2018-iteration-2/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.0_dist_0.0-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.2_dist_0.9-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.4_dist_0.3-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.4_dist_0.6-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.4_dist_1.0-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.6_dist_0.9-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_1.0_dist_0.9-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_1.0_dist_1.0-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-5veh__2022-07-20_21-48-51_yti/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-10veh__2022-07-20_21-48-52_ucj/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-20veh__2022-07-20_21-48-54_xwf/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-50veh__2022-07-20_21-49-07_oyq/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-100veh__2022-07-20_21-49-00_lgi/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-5veh__2022-07-15_04-13-50_ntn/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-10veh__2022-07-15_04-13-44_okn/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-20veh__2022-07-15_04-13-46_szz/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-50veh__2022-07-15_04-13-48_qbr/ITERS/it.2/2.events.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-100veh__2022-07-15_04-13-44_apr/ITERS/it.2/2.events.csv.gz',
#              ]

# plan_names = [
#                 'pilates-outputs/sfbay-base-20220409/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_0.125-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_0.25-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_0.5-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-RH_fleetsz_1.75-20220408/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-transit_frequencies_0.5-20220528/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-transit_frequencies_1.5-20220529/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-transit_frequencies_2.0-20220529/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_0.0-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_0.5-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_1.5-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_repo_3.0-20220613/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_0.2-20220610/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_0.5-20220610/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_1.5-20220614/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_radius_5.0-20220616/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_0.2-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_0.5-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_0.8-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_wt_1.6-20220617/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_0.0-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_0.5-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_0.75-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_detour_1.5-20220618/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-pilates_june_test-20220616/beam/year-2010-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_price_0.5-20220616/beam/year-2010-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-1T-20220721/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-1T-SF-VT-20220722/beam/year-2018-iteration-2/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.0_dist_0.0-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.2_dist_0.9-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.4_dist_0.3-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.4_dist_0.6-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.4_dist_1.0-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_0.6_dist_0.9-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_1.0_dist_0.9-20220627/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
                # 'pilates-outputs/sfbay-rh_invrepo_dem_1.0_dist_1.0-20220628/beam/year-2018-iteration-5/ITERS/it.0/0.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-5veh__2022-07-20_21-48-51_yti/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-10veh__2022-07-20_21-48-52_ucj/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-20veh__2022-07-20_21-48-54_xwf/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-50veh__2022-07-20_21-49-07_oyq/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-100veh__2022-07-20_21-49-00_lgi/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-5veh__2022-07-15_04-13-50_ntn/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-10veh__2022-07-15_04-13-44_okn/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-20veh__2022-07-15_04-13-46_szz/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-50veh__2022-07-15_04-13-48_qbr/ITERS/it.2/2.plans.csv.gz',
#                 'output/sfbay/sfbay-pilates-50pop-100veh__2022-07-15_04-13-44_apr/ITERS/it.2/2.plans.csv.gz',
#              ]
# names = [         
#                 'Baseline/year-2018-iteration-5',
                # 'sfbay-RH_fleetsz_0.125',
                # 'sfbay-RH_fleetsz_0.25',
                # 'sfbay-RH_fleetsz_0.5',
                # 'sfbay-RH_fleetsz_1.75',
                # 'sfbay-transit_frequencies_0.5',
                # 'sfbay-transit_frequencies_1.5',
                # 'sfbay-transit_frequencies_2.0',
                # 'sfbay-rh_repo_0.0',
                # 'sfbay-rh_repo_0.5',
                # 'sfbay-rh_repo_1.5',
                # 'sfbay-rh_repo_3.0',
                # 'sfbay-rh_radius_0.2',
                # 'sfbay-rh_radius_0.5',
                # 'sfbay-rh_radius_1.5',
                # 'sfbay-rh_radius_5.0',
                # 'sfbay-rh_wt_0.2',
                # 'sfbay-rh_wt_0.5',
                # 'sfbay-rh_wt_0.8',
                # 'sfbay-rh_wt_1.6',
                # 'sfbay-rh_detour_0.0',
                # 'sfbay-rh_detour_0.5',
                # 'sfbay-rh_detour_0.75',
                # 'sfbay-rh_detour_1.5',
                # 'sfbay-cp_pilatesJune2022',
                # 'sfbay-rh_price_0.5',
                # '1TEMPO',
                # '1T-SF-VT',
                # 'sfbay-rh_invrepo_dem_0.0_dist_0.0',
                # 'sfbay-rh_invrepo_dem_0.2_dist_0.9',
                # 'sfbay-rh_invrepo_dem_0.4_dist_0.3',
                # 'sfbay-rh_invrepo_dem_0.4_dist_0.6',
                # 'sfbay-rh_invrepo_dem_0.4_dist_1.0',
                # 'sfbay-rh_invrepo_dem_0.6_dist_0.9',
                # 'sfbay-rh_invrepo_dem_1.0_dist_0.9',
                # 'sfbay-rh_invrepo_dem_1.0_dist_1.0',
                # 'Disabilities 5% RH WC 50% RH fleet',
                # 'Disabilities 10% RH WC 50% RH fleet',
                # 'Disabilities 20% RH WC 50% RH fleet',
                # 'Disabilities 50% RH WC 50% RH fleet',
                # 'Disabilities 100% RH WC 50% RH fleet',
                # 'Disabilities 5% RH WC 100% RH fleet',
                # 'Disabilities 10% RH WC 100% RH fleet',
                # 'Disabilities 20% RH WC 100% RH fleet',
                # 'Disabilities 50% RH WC 100% RH fleet',
                # 'Disabilities 100% RH WC 100% RH fleet']
#######################################################################################################################

# Nomenclature
PTsColumns = [
    'vehicle','time','type','mode','length','vehicleType','arrivalTime','departureTime',
        'capacity','secondaryFuel','primaryFuelType','secondaryFuelType','numPassengers','primaryFuel',
        ]
MCsColumns = ['person','time','type','mode','length']

summaryTable = pd.DataFrame()

PTsModes = np.array(['walk','bike','car','car_RideHail','car_RideHail_empty','car_RideHail_WC','car_RideHail_WC_empty',
                               'car_CAV','car_hov2','car_hov3','bus','tram','rail','subway',
                               'cable_car','ferry','bus_empty','tram_empty','rail_empty',
                               'subway_empty','cable_car_empty','ferry_empty'])

PTsModesNames = ['Walk','Bike','Car','Ride Hail','Empty Ride Hail','Ride Hail WC','Empty Ride Hail WC',
                           'CAV','Car HOV2','Car HOV3','Bus','Tram','Rail','Subway',
                           'Cable Car','Ferry','Empty Bus','Empty Tram','Empty Rail',
                           'Empty Subway','Empty Cable Car','Empty Ferry',]


transit_modes = ['bus', 'subway', 'tram', 'rail','cable_car', 'ferry']

transit_MCmodes = ['bus', 'subway', 'tram', 'rail', 'walk_transit', 'ride_hail_transit',
                            'drive_transit', 'cable_car','bike_transit']

MCsModes = np.array([ 'bus', 'subway', 'tram', 'rail', 'car', 'hov3_teleportation', 
                            'bike', 'hov2_teleportation', 'walk', 'car_hov2', 'car_hov3', 
                            'walk_transit', 'ride_hail', 'ride_hail_transit', 'ride_hail_pooled', 
                            'drive_transit', 'cable_car','bike_transit'])

MCsModesNames = [ 'Bus', 'Subway', 'Tram', 'Rail', 'Car', 'HOV3 Passenger', 'Bike', 
                   'HOV2 Passenger', 'Walk', 'HOV2 Driver', 'HOV3 Driver', 
                   'Walk-Transit', 'Ride Hail', 'Ride Hail-Transit', 'Ride Hail Pooled', 
                   'Drive-Transit', 'Cable Car', 'Bike-Transit']

primaryFuelTypes =['Biodiesel','Diesel','Gasoline','Electricity','Food']

CtV = {0:'SUM',
      1:'AV',
      2:'VAR',
      3:'STD',
      4:'MIN',
      5:'Q1ST',
      6:'Q2ND',
      7:'Q3RD',
      8:'MAX',
      }


# In[4]:


start = time.time()
print('Start time',start, 's')


# In[5]:


def DA(data, code):
    
    #data is a list of values
    #code - operation
    #0 - Sum
    #1 - Mean
    #2 - Var
    #3 - Std error
    #4 - Min
    #5 - Q1
    #6 - Q2
    #7 - Q3
    #8 - Max
    if len(data)>0:
        if code == 0:
            value = np.sum(data)
        elif code == 1:
            value = np.mean(data)
        elif code == 2:
            value = np.var(data)
        elif code == 3:
            value = np.std(data)
        elif code == 4:
            value = np.min(data)
        elif code == 5:
            value = np.percentile(data, 25)
        elif code == 6:
            value = np.percentile(data, 50)
        elif code == 7:
            value = np.percentile(data, 75)
        elif code == 8:
            value = np.max(data)
    else:
        value = np.nan

        
    return value


# In[6]:


# PtoPTss = personToPathTraversal(PT s,PEVs,PLVs,personToTripDepartures)


# In[7]:


def personToPathTraversal(PTs, PEVs, PLVs, personToTripDeparture):
    print('personToPathTraversal...')
    no_legs_after_time_check = []
    no_legs = []
    start = time.time()
    for pt_mode in PTs['mode'].value_counts().keys():
        print('expected PtoPTs from occupancy ',pt_mode,  int(np.sum(PTs[PTs['mode']==pt_mode]['occupancy'])))
    print('expected PtoPTs from occupancy TOT = ', np.sum(PTs['occupancy']))
    print('len of PEV = ', len(PEVs))
    print('len of PLV = ', len(PLVs))

    vehicleToPT = PTs.groupby('vehicle').apply(lambda x: list(x.index)).apply(
        lambda x: {y: [] for y in x}).to_dict()
    PEVlookup = PEVs[['person', 'vehicle', 'time']].value_counts().to_dict()
    PLVlookup = PLVs.groupby(['person', 'vehicle']).apply(lambda x: list(x.time)).to_dict()

    for key, counts in PEVlookup.items():
        person = key[0]
        vehicle = key[1]
        departureTime = key[2]
        n_new_leg = 0
        if vehicle in vehicleToPT:
            legs = vehicleToPT[vehicle]
            if (person, vehicle) in PLVlookup:
                if person in personToTripDeparture:
                    planTrips = personToTripDeparture[person]
                    tripsLeavingBeforeDeparture = [-1] + [t['planID'] for t in planTrips if
                                                          t['departureTime'] <= (departureTime+1)]
                                                                                     #+ 1800)]
                else:
                    tripsLeavingBeforeDeparture = [-1]
                    print('no person',person, 'on personToTripDeparture...','vehicle',vehicle,'departureTime',departureTime,'personToTripDeparture',personToTripDeparture[person])

                lastTripBeforeDeparture = tripsLeavingBeforeDeparture[-1]
                if lastTripBeforeDeparture == -1:
                    print('hmm...if person on personToTripDeparture, no plans starting before departure')
                
                endTimes = PLVlookup[(person, vehicle)]
                plvsAfterDeparture = [t for t in endTimes if t > departureTime]
                if len(plvsAfterDeparture) > 0:
                    firstPLVafterDeparture = plvsAfterDeparture[0]
                    n_new_leg = 0
                    for leg in legs.keys():
                        ptDepartureTime = PTs.at[leg, 'departureTime']
                        if (ptDepartureTime >= departureTime) & (ptDepartureTime < firstPLVafterDeparture):
                            n_new_leg +=1
                            legs[leg].append((person, lastTripBeforeDeparture))
                            
                else:
                    for leg in legs.keys():
                        n_new_leg = 0
                        ptDepartureTime = PTs.at[leg, 'departureTime']
                        if ptDepartureTime >= departureTime:
                            n_new_leg +=1
                            legs[leg].append((person, lastTripBeforeDeparture))
            else:

                if person in personToTripDeparture:
                    planTrips = personToTripDeparture[person]
                    tripsLeavingBeforeDeparture = [-1] + [t['planID'] for t in planTrips if
                                                          t['departureTime'] <= (departureTime+1)]
                                                                                     #+ 1800)]
                else:
                    tripsLeavingBeforeDeparture = [-1]
                    print('no person',person, 'on personToTripDeparture...','vehicle',vehicle,'departureTime',departureTime,'personToTripDeparture',personToTripDeparture[person])

                lastTripBeforeDeparture = tripsLeavingBeforeDeparture[-1]
                if lastTripBeforeDeparture == -1:
                    print('hmm...if person on personToTripDeparture, no plans starting before departure')
                n_new_leg = 0
                for leg in legs.keys():
                    ptDepartureTime = PTs.at[leg, 'departureTime']
                    if ptDepartureTime >= departureTime:
                        n_new_leg +=1
                        legs[leg].append((person, lastTripBeforeDeparture))
            if n_new_leg == 0:
                no_legs_after_time_check.append(vehicle)
#                 print("Warning: no vehicle legs (after time check) for person, vehicle, depTime", person, vehicle, departureTime)
        else:
                no_legs.append(vehicle)
#             print("Warning: no vehicle legs for person, vehicle, depTime", person, vehicle, departureTime)

    PtoPTssList = [(veh, pathTraversalID, passenger, planIndex) for veh, vehicleLegs in
                                vehicleToPT.items() for pathTraversalID, passengers in vehicleLegs.items() for
                                (passenger, planIndex) in passengers if len(passengers) > 0]
    PtoPTss = pd.MultiIndex.from_tuples(PtoPTssList,
                                                     name=['vehicleID', 'pathTraversalID', 'personID',
                                                           'planIndex']).to_frame()
    modes_PtoPTss = []
    lengths_PtoPTss = []
    durations_PtoPTss = []
    prim_fuel_type_PtoPTss = []
    
    for pt_id in PtoPTss['pathTraversalID']:
        modes_PtoPTss.append(PTs.at[pt_id,'mode'])
        lengths_PtoPTss.append(PTs.at[pt_id,'length'])
        durations_PtoPTss.append(PTs.at[pt_id,'duration'])
        prim_fuel_type_PtoPTss.append(PTs.at[pt_id,'primaryFuelType'])

    PtoPTss['mode'] = modes_PtoPTss
    PtoPTss['length'] = lengths_PtoPTss
    PtoPTss['duration'] = durations_PtoPTss
    PtoPTss['primaryFuelType'] = prim_fuel_type_PtoPTss

    vehicles_2 = []
    for pt_id in PtoPTss['pathTraversalID']:
        vehicles_2.append(PTs.at[pt_id,'vehicle'][:len_id_transit])
    vehicles_2 = np.array(vehicles_2)
    PtoPTss['vehicle2'] = vehicles_2
    
    modes,counts = np.unique(modes_PtoPTss, return_counts = True)
    for mode, count in zip(modes, counts):
        print('len PtoPTs after matching agents and vehicles',mode,count)
        
    print('no legs found:', len(no_legs))
    print('no legs found after time check:', len(no_legs_after_time_check))
    print('no vehicle body found, probably because with zero duration (discarded bu PT):', 
          len(list(filter(lambda k: 'body' in k, no_legs))))
    print('no vehicle body found after time check, probably because with zero duration (discarded bu PT):', 
          len(list(filter(lambda k: 'body' in k, no_legs_after_time_check))))
    print('Tot created PtoPTs = ', len(PtoPTss))
    PtoPTss.index = range(len(PtoPTss))
    print('Total time:', time.time()-start)
    return PtoPTss


# In[8]:


def processPlans(directory):
    #fullPath = directory + 'plans.csv.gz'
    print('processPlans...')
    start = time.time()
    trips = []
    activities = []
    personToTripDeparture = {}
    print(directory)
    df = pd.read_csv(directory, nrows = None)
    # print(df.keys())
    df = df[df['planSelected']==True]
    # print(df[df['personId']==194])
    df = addTimesToPlans(df)
    legs = df.loc[(df['planElementType'].str.lower().str.contains('leg'))].dropna(how='all', axis=1)
    legs = (legs[legs['legDepartureTime']>=0])
    # print(legs.keys())
    # print(legs)
    try:
        legsSub = legs[['personId', 'legDepartureTime',  'planElementIndex', 'legMode', 'originX', 'originY', 'destinationX', 'destinationY']]
        is_leg_mode = True
    except:
        legsSub = legs[['personId', 'legDepartureTime',  'planElementIndex', 'originX', 'originY', 'destinationX', 'destinationY']]
        is_leg_mode = False

    for rowID, val in legsSub.iterrows():
        personToTripDeparture.setdefault(val.personId, []).append(
            {"planID": val.planElementIndex, "departureTime": val.legDepartureTime})
    #TRIPS
    trips.append(legsSub)
    #ACTS
    acts = df.loc[(df['planElementType'].str.lower().str.contains('activity'))].dropna(how='all', axis=1)
    actsSub = acts[['personId', 'activityType', 'activityLocationX', 'activityLocationY', 'activityEndTime']]
    activities.append(actsSub)
    print('Total time:', time.time()-start)
    return pd.concat(trips), pd.concat(activities), personToTripDeparture, is_leg_mode


# In[9]:


def addTimesToPlans(plans):
    print('addTimesToPlans...')
    start = time.time()
    legInds = np.where(plans['planElementType'].str.lower() == "leg")[0]
    plans.loc[:, 'legDepartureTime'] = np.nan
    plans.iloc[legInds, plans.columns.get_loc('legDepartureTime')] = plans['activityEndTime'].iloc[legInds - 1].copy()
    plans.loc[:, 'originX'] = np.nan
    plans.iloc[legInds, plans.columns.get_loc('originX')] = plans['activityLocationX'].iloc[legInds - 1].copy()
    plans.loc[:, 'originY'] = np.nan
    plans.iloc[legInds, plans.columns.get_loc('originY')] = plans['activityLocationY'].iloc[legInds - 1].copy()
    plans.loc[:, 'destinationX'] = np.nan
    plans.iloc[legInds, plans.columns.get_loc('destinationX')] = plans['activityLocationX'].iloc[legInds + 1].copy()
    plans.loc[:, 'destinationY'] = np.nan
    plans.iloc[legInds, plans.columns.get_loc('destinationY')] = plans['activityLocationY'].iloc[legInds + 1].copy()
    print('Total time:', time.time()-start)
    return plans


# In[10]:


def readEvents(directory):
    #fullPath = directory + 'ITERS/it.0/0.events.csv.gz'
    PTs = []
    PEVs = []
    PLVs = []
    MCs = []
    RPs = []
    print('Reading ', directory)
    for chunk in pd.read_csv(directory, chunksize=2500000, nrows = nrows):
        if sum((chunk['type'] == 'PathTraversal')) > 0:
            chunk['vehicle'] = chunk['vehicle'].astype(str)
            
            #PT
            print(len(chunk.loc[(chunk['type'] == 'PathTraversal')]),': len chunk PT')
            PT = chunk.loc[(chunk['type'] == 'PathTraversal') & (chunk['length'] > 0)].dropna(how='all', axis=1)
            PT['departureTime'] = PT['departureTime'].astype(int)
            PT['arrivalTime'] = PT['arrivalTime'].astype(int)
            PTs.append(PT[PTsColumns])
            print(len(PT),': after filtering zero-length PT')
            #PEV
            print(len(chunk.loc[(chunk['type'] == 'PersonEntersVehicle')]),': len chunk PEV')
#             PEV = chunk.loc[(chunk.type == "PersonEntersVehicle") & 
#                             ~(chunk['person'].apply(str).str.contains('Agent').fillna(False)) & 
#                             ~(chunk['vehicle'].str.contains('body').fillna(False)), :].dropna(how='all', axis=1)
            PEV = chunk.loc[(chunk.type == "PersonEntersVehicle") &
                            ~(chunk['person'].apply(str).str.contains('Agent').fillna(False))
                            , :].dropna(how='all', axis=1)
            print(len(PEV),': after filtering drivers')
                                                                                                                    
            if ~PEV.empty:
                PEV['person'] = PEV['person'].astype(int)
                PEV['time'] = PEV['time'].astype(int)
                PEVs.append(PEV)

            #PLV
#             PLV = chunk.loc[(chunk.type == "PersonLeavesVehicle") & 
#                             ~(chunk['person'].apply(str).str.contains('Agent').fillna(False)) & 
#                             ~(chunk['vehicle'].str.contains('body').fillna(False)), :].dropna(how='all', axis=1)
            print(len(chunk.loc[(chunk['type'] == 'PersonLeavesVehicle')]),': len chunk PLV')
            PLV = chunk.loc[(chunk.type == "PersonLeavesVehicle") &
                            ~(chunk['person'].apply(str).str.contains('Agent').fillna(False))
                            , :].dropna(how='all', axis=1) 
            print(len(PLV),': after filtering drivers')
            if ~PLV.empty:
                PLV['person'] = PLV['person'].astype(int)
                PLV['time'] = PLV['time'].astype(int)
                PLVs.append(PLV)
        if sum((chunk['type'] == 'ModeChoice')) > 0:
            #MC
            MC = chunk.loc[(chunk['type'] == 'ModeChoice') & (chunk['length'] > 0)].dropna(how='all', axis=1)
            MCs.append(MC[MCsColumns])
            
        if sum((chunk['type'] == 'Replanning')) > 0:
            #RP
            RP = chunk.loc[(chunk['type'] == 'Replanning')].dropna(how='all', axis=1)
            RPs.append(RP)
        
    print(len(pd.concat(PEVs)),':len PEVs')
    print(len(pd.concat(PLVs)),':len PLVs')
    
    PEVs = pd.concat(PEVs)
    PLVs = pd.concat(PLVs)
    PTs = pd.concat(PTs)
    MCs = pd.concat(MCs)
    RPs = pd.concat(RPs)

    
    print(len(PTs),':len PTs')
    print(len(MCs),':len MCs')
    print(len(RPs),':len RPs')

    
    return MCs, PTs, PEVs, PLVs, RPs


# In[11]:


def fixData(Mcs, PTs, PEVs, PLVs,len_id_transit):

    PTs['duration'] = PTs['arrivalTime'] - PTs['departureTime']
    PTs['gallonsGasoline'] = 0
    PTs.loc[PTs['primaryFuelType'] == 'Gasoline',
            'gallonsGasoline'] += PTs.loc[PTs['primaryFuelType'] == 'Gasoline', 'primaryFuel'] * 8.3141841e-9
    PTs.loc[PTs['secondaryFuelType'] == 'Gasoline',
            'gallonsGasoline'] += PTs.loc[PTs['secondaryFuelType'] == 'Gasoline', 'secondaryFuel'] * 8.3141841e-9
    PTs['occupancy'] = PTs['numPassengers']
    
    PTs['isCAV'] = PTs['vehicleType'].str.contains('L5')
    PTs['isRH'] = PTs['vehicle'].str.contains('rideHail')
    PTs['isRH_WC'] = PTs['vehicleType'].str.contains('RH_Car-wheelchair')
    PTs['is_empty'] = PTs['numPassengers'] == 0
    PTs['is_RHempty'] = PTs['isRH']*PTs['is_empty']
    PTs.loc[PTs['isRH'], 'mode'] += '_RideHail'
    PTs.loc[PTs['isRH_WC'], 'mode'] += '_WC'
    PTs.loc[PTs['isCAV'], 'mode'] += '_CAV'
    PTs.loc[PTs['is_RHempty'], 'mode'] += '_empty'

    PTs.loc[PTs['mode'] == 'car', 'occupancy'] += 1
    PTs.loc[PTs['mode'] == 'car_hov2', 'occupancy'] += 1
    PTs.loc[PTs['mode'] == 'car_hov3', 'occupancy'] += 1
    PTs.loc[PTs['mode'] == 'walk', 'occupancy'] = 1
    PTs.loc[PTs['mode'] == 'bike', 'occupancy'] = 1
    
    PTs.loc[PTs['mode'] == 'car', 'capacity'] += 1
    PTs.loc[PTs['mode'] == 'car_hov2', 'capacity'] += 1
    PTs.loc[PTs['mode'] == 'car_hov3', 'capacity'] += 1
    PTs.loc[PTs['mode'] == 'walk', 'capacity'] = 1
    PTs.loc[PTs['mode'] == 'bike', 'capacity'] = 1
    
    for tm in transit_modes:
        PTs['is'+tm] = PTs['mode'].str.contains(tm)
    for tm in transit_modes:
        PTs['is_'+tm+'_empty'] = PTs['is'+tm]*PTs['is_empty']
    PTs['is_transit'] = 0
    for tm in transit_modes:
        PTs['is_transit']+=PTs['is'+tm]
    for tm in transit_modes:
        PTs.loc[PTs['is_'+tm+'_empty'], 'mode'] += '_empty'
        PTs.drop(columns=['is'+tm])
        PTs.drop(columns=['is_'+tm+'_empty'])
        
    PTs.drop(columns=['isCAV','is_empty','is_RHempty',])
    
    vehicles_2 = []
    vehicles = PTs['vehicle']
    for vehicle in vehicles:
        vehicles_2.append(vehicle[:len_id_transit])
    vehicles_2 = np.array(vehicles_2)
    PTs['vehicle2'] = vehicles_2
    
    vehicles_2 = []
    vehicles = PEVs['vehicle']
    for vehicle in vehicles:
        vehicles_2.append(vehicle[:len_id_transit])
    PEVs['vehicle2'] = vehicles_2
    
    vehicles_2 = []
    vehicles = PLVs['vehicle']
    for vehicle in vehicles:
        vehicles_2.append(vehicle[:len_id_transit])
    PLVs['vehicle2'] = vehicles_2
    
    return Mcs, PTs, PEVs, PLVs



# In[12]:


def SummaryTable(ST, data_name, name, plan_name, MCs, PTs, PEVs, PLVs, RPs, trips, PtoPTss, codes, transitCompanies):
    
#----------Indexes
    PTsModeIndexes = {}
    PTsTransitIndexes = {}
    PTsFuelIndexes = {}
    MCsModeIndexes = {}
    MCsReplanIndexes = {}
    MCsPlanIndexes = {}
    PtoPTssModeIndexes = {}
    PtoPTssTransitIndexes = {}
    PtoPTssFuelIndexes = {}
    #Replanning
    reasons = []
    for reason in RPs['reason']:
        reasons.append(reason.split()[1].lower())
    RPs['mode'] = reasons
    totalTrips_replan = len(RPs['mode'])
    
    for MCsMode, MCsModesName in zip(MCsModes, MCsModesNames):
        MCsReplanIndexes[MCsMode] = RPs[(RPs['mode'] == MCsMode)].index
    for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
        PTsModeIndexes[PTsMode] = PTs[(PTs['mode'] == PTsMode)].index
        PtoPTssModeIndexes[PTsMode] = PtoPTss[(PtoPTss['mode'] == PTsMode)].index
    for company in transitCompanies:
        PTsTransitIndexes[company] = PTs[(PTs['vehicle2'] == company)].index
        PtoPTssTransitIndexes[company] = PtoPTss[(PtoPTss['vehicle2'] == company)].index
    for primaryFuelType in primaryFuelTypes:
        PTsFuelIndexes[primaryFuelType] = PTs[(PTs['primaryFuelType'] == primaryFuelType)].index
        PtoPTssFuelIndexes[primaryFuelType] = PtoPTss[(PtoPTss['primaryFuelType'] == primaryFuelType)].index
    for MCsMode, MCsModesName in zip(MCsModes, MCsModesNames):
        MCsModeIndexes[MCsMode] = MCs[(MCs['mode'] == MCsMode)].index
    if is_leg_mode:
        for MCsMode, MCsModesName in zip(MCsModes, MCsModesNames):
            MCsPlanIndexes[MCsMode] = trips[(trips['legMode'] == MCsMode)].index

    ST.at['Simulated Agents ', name] = len(pd.unique(trips['personId'])) 
    ST.at['Trips per Agent AV ', name] = len(trips)/len(pd.unique(trips['personId']))
    
#----------Number Trips
#check plans for estimated mode share, trip per person
    totalTrips_vehicle = len(PTs['mode'])
    totalTrips_est = len(trips)
    totalTrips_mode = len(MCs['mode'])
    totalTrips_replan = len(RPs)
    totalTrips_exec = totalTrips_mode-len(RPs)
    
    print('Number Trips...',name)
    ST.at['Trip Vehicle Total ', name] = totalTrips_vehicle
    ST.at['Trip Est Total ', name] = totalTrips_est
    ST.at['Trip Mode Total ', name] = totalTrips_mode
    ST.at['Trip Replanning Total ', name] = totalTrips_replan
    ST.at['Trip Exectuted Total ', name] = totalTrips_exec

    
    
    for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
        ST.at['Trip Vehicle '+PTsModesName, name] = len(PTsModeIndexes[PTsMode])
    if is_leg_mode:
        for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
            ST.at['Trip Est '+MCsName, name] = len(MCsPlanIndexes[MCsMode])
    for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
        ST.at['Trip Mode '+MCsName, name] = len(MCsModeIndexes[MCsMode])
    for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
        ST.at['Trip Replan '+MCsName, name] = len(MCsReplanIndexes[MCsMode])
    transit_exec = 0
    for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
        ST.at['Trip Exec '+MCsName, name] = len(MCsModeIndexes[MCsMode])-len(MCsReplanIndexes[MCsMode])
        if MCsMode in transit_MCmodes:
            transit_exec += len(MCsModeIndexes[MCsMode])-len(MCsReplanIndexes[MCsMode])
    for primaryFuelType in primaryFuelTypes:
        ST.at['Trip Vehicle '+primaryFuelType, name] = len(PTsFuelIndexes[primaryFuelType])
    for company in transitCompanies:
        ST.at['Trip Vehicle '+company, name] = len(PTsTransitIndexes[company])
        
#----------Share Trips
    print('Share Trips...',name)
    for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
        ST.at['Trip Vehicle Share '+PTsModesName, name] = len(PTsModeIndexes[PTsMode])/totalTrips_vehicle
    for company in transitCompanies:
        ST.at['Trip Vehicle Share '+company, name] = len(PTsTransitIndexes[company])/totalTrips_vehicle
    if is_leg_mode:
        for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
            ST.at['Trip Est Share '+MCsName, name] = len(MCsPlanIndexes[MCsMode])/totalTrips_est
    for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
        ST.at['Trip Mode Share '+MCsName, name] = len(MCsModeIndexes[MCsMode])/totalTrips_mode
    for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
        ST.at['Trip Replan Share '+MCsName, name] = len(MCsReplanIndexes[MCsMode])/totalTrips_replan
    for MCsMode, MCsName in zip(MCsModes, MCsModesNames):
        ST.at['Trip Exec Share '+MCsName, name] = (len(MCsModeIndexes[MCsMode])-len(MCsReplanIndexes[MCsMode]))/totalTrips_exec
    for primaryFuelType in primaryFuelTypes:
        ST.at['Trip Vehicle Share '+primaryFuelType, name] = len(PTsFuelIndexes[primaryFuelType])/totalTrips_vehicle



#----------Trip Lengths
    #----------------------Vehicles
    print('Lengths Vehicles...',name)
    lengths = PTs['length']/1000.
    for code in codes:
        ST.at['Length Vehicle'+CtV[code]+' [km]', name] = DA(lengths, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            lengths_mode = lengths[PTsModeIndexes[PTsMode]]
            ST.at['Length Vehicle '+CtV[code]+' '+PTsModesName+' [km]', name] = DA(lengths_mode, code)
        for company in transitCompanies:
            lengths_company = lengths[PTsTransitIndexes[company]]
            ST.at['Length Vehicle '+CtV[code]+' '+company+' [km]', name] = DA(lengths_company, code)
        for primaryFuelType in primaryFuelTypes:
            lengths_fueltype = lengths[PTsFuelIndexes[primaryFuelType]]
            ST.at['Length Vehicle '+CtV[code]+' '+primaryFuelType+' [km]', name] = DA(lengths_fueltype, code)  
    
    #----------------------Persons
    print('Lengths Persons...',name)
    lengths = PtoPTss['length']/1000.
    for code in codes:
        ST.at['Length Person'+CtV[code]+' [km]', name] = DA(lengths, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            lengths_mode = lengths[PtoPTssModeIndexes[PTsMode]]
            ST.at['Length Person '+CtV[code]+' '+PTsModesName+' [km]', name] = DA(lengths_mode, code)
        for company in transitCompanies:
            lengths_company = lengths[PtoPTssTransitIndexes[company]]
            ST.at['Length Person '+CtV[code]+' '+company+' [km]', name] = DA(lengths_company, code)
        for primaryFuelType in primaryFuelTypes:
            lengths_fueltype = lengths[PtoPTssFuelIndexes[primaryFuelType]]
            ST.at['Length Person '+CtV[code]+' '+primaryFuelType+' [km]', name] = DA(lengths_fueltype, code)  
    
    
    #----------------------Modes
    print('Lengths Modes...',name)
    lengths = MCs['length']/1000.
    for code in codes:
        ST.at['Length Trip '+CtV[code]+'[km]', name] = DA(lengths, code)
        for MCsMode, MCsModesName in zip(MCsModes, MCsModesNames):
            lengths_mode = lengths[MCsModeIndexes[MCsMode]]
            ST.at['Length Mode '+CtV[code]+' '+MCsModesName+' [km]', name] = DA(lengths_mode, code)
   
    lengths = PTs['length']/1000.
    
 #----------Trip Durations
    print('Durations Vehicle...',name)
    durations = PTs['duration']/3600.
    for code in codes:
        ST.at['Duration Vehicle'+CtV[code]+' [h]', name] = DA(durations, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            durations_mode = durations[PTsModeIndexes[PTsMode]]
            ST.at['Duration Vehicle '+CtV[code]+' '+PTsModesName+' [h]', name] = DA(durations_mode, code)
        for company in transitCompanies:
            durations_company = durations[PTsTransitIndexes[company]]
            ST.at['Duration Vehicle '+CtV[code]+' '+company+' [h]', name] = DA(durations_company, code)
        for primaryFuelType in primaryFuelTypes:
            durations_fueltype = durations[PTsFuelIndexes[primaryFuelType] ]
            ST.at['Duration Vehicle '+CtV[code]+' '+primaryFuelType+' [h]', name] = DA(durations_fueltype, code)   
     
    #----------Persons
    print('Durations Person...',name)
    durations = PtoPTss['duration']/3600.
    for code in codes:
        ST.at['Duration Vehicle'+CtV[code]+' [h]', name] = DA(durations, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            durations_mode = durations[PtoPTssModeIndexes[PTsMode]]
            ST.at['Duration Vehicle '+CtV[code]+' '+PTsModesName+' [h]', name] = DA(durations_mode, code)
        for company in transitCompanies:
            durations_company = durations[PtoPTssTransitIndexes[company]]
            ST.at['Duration Vehicle '+CtV[code]+' '+company+' [h]', name] = DA(durations_company, code)
        for primaryFuelType in primaryFuelTypes:
            durations_fueltype = durations[PtoPTssFuelIndexes[primaryFuelType] ]
            ST.at['Duration Vehicle '+CtV[code]+' '+primaryFuelType+' [h]', name] = DA(durations_fueltype, code)   
    
    
#----------Trip Speeds
    print('Speeds Vehicle...',name)
    speeds = lengths/durations[(durations>0)]
    for code in codes:
        ST.at['Speed Vehicle'+CtV[code]+' [km/h]', name] = DA(speeds, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            speeds_mode = speeds[PTsModeIndexes[PTsMode]]
            ST.at['Speed Vehicle '+CtV[code]+' '+PTsModesName+' [km/h]', name] = DA(speeds_mode, code)
        for company in transitCompanies:
            speeds_company = speeds[PTsTransitIndexes[company]]
            ST.at['Speed Vehicle '+CtV[code]+' '+company+' [km/h]', name] = DA(speeds_company, code)
        for primaryFuelType in primaryFuelTypes:
            speeds_fueltype = speeds[PTsFuelIndexes[primaryFuelType] ]
            ST.at['Speed Vehicle '+CtV[code]+' '+primaryFuelType+' [km/h]', name] = DA(speeds_fueltype, code)   
    
#----------Energy Consumption
    print('Energy Usage Vehicle...',name)
    energies = PTs['primaryFuel']/1000000000.+PTs['secondaryFuel']/1000000000.
    for code in codes:
        ST.at['Energy Vehicle'+CtV[code]+' [GJ]', name] = DA(energies, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            energies_mode = energies[PTsModeIndexes[PTsMode]]
            ST.at['Energy Vehicle '+CtV[code]+' '+PTsModesName+' [GJ]', name] = DA(energies_mode, code)
        for company in transitCompanies:
            energies_company = energies[PTsTransitIndexes[company]]
            ST.at['Energy Vehicle '+CtV[code]+' '+company+' [GJ]', name] = DA(energies_company, code)
        for primaryFuelType in primaryFuelTypes:
            energies_fueltype = energies[PTsFuelIndexes[primaryFuelType] ]
            ST.at['Energy Vehicle '+CtV[code]+' '+primaryFuelType+' [GJ]', name] = DA(energies_fueltype, code)   

#----------Trip Gallons
    print('Trip Gallons Vehicle...',name)
    gallons = PTs['gallonsGasoline']
    for code in codes:
        ST.at['Gallons Gas Vehicle'+CtV[code]+' [gallon]', name] = DA(gallons, code)
        for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
            gallons_mode = gallons[PTsModeIndexes[PTsMode]]
            ST.at['Gallons Gas Vehicle '+CtV[code]+' '+PTsModesName+' [gallon]', name] = DA(gallons_mode, code)
        for company in transitCompanies:
            gallons_company = gallons[PTsTransitIndexes[company]]
            ST.at['Gallons Gas Vehicle '+CtV[code]+' '+company+' [gallonh]', name] = DA(gallons_company, code)
        for primaryFuelType in primaryFuelTypes:
            gallons_fueltype = gallons[PTsFuelIndexes[primaryFuelType] ]
            ST.at['Gallons Gas Vehicle '+CtV[code]+' '+primaryFuelType+' [gallon]', name] = DA(gallons_fueltype, code)   
    
#----------Occupancy
    print('Occupancy Vehicle...',name)
    passengers = PTs['occupancy']
    capacities = PTs['capacity']

    for company in transitCompanies:
        passenger_company = passengers[PTsTransitIndexes[company]]
        ST.at['Vehicle Passengers stops '+company, name] = np.sum(passenger_company)

    for company in transitCompanies:
        passenger_company = passengers[PTsTransitIndexes[company]]
        lengths_company = lengths[PTsTransitIndexes[company]]
        ST.at['Vehicle Passengers km '+company, name] = np.sum(passenger_company*lengths_company)

    for company in transitCompanies:
        lengths_company = lengths[PTsTransitIndexes[company]]
        capacities_company = capacities[PTsTransitIndexes[company]]
        ST.at['Vehicle Capacity km '+company, name] = np.sum(capacities_company*lengths_company)

    for company in transitCompanies:
        passenger_company = passengers[PTsTransitIndexes[company]]
        lengths_company = lengths[PTsTransitIndexes[company]]
        capacities_company = capacities[PTsTransitIndexes[company]]
        if np.sum(capacities_company*lengths_company)>0:
            ST.at['Vehicle Load Factor '+company, name] = np.sum(passenger_company*lengths_company)/np.sum(capacities_company*lengths_company)

    ST.at['Vehicle Person km Total ', name] = np.sum(lengths*passengers)

    for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
        if PTsMode != 'bike' and PTsMode != 'walk':
            lengths_mode = lengths[PTsModeIndexes[PTsMode]]
            passengers_mode = passengers[PTsModeIndexes[PTsMode]] 
            ST.at['Vehicle Person km '+PTsModesName, name] = np.sum(lengths_mode*passengers_mode)

    for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
        if PTsMode != 'bike' and PTsMode != 'walk':
            lengths_mode = lengths[PTsModeIndexes[PTsMode]]
            capacities_mode = capacities[PTsModeIndexes[PTsMode]] 
            ST.at['Vehicle Capacity km '+PTsModesName, name] = np.sum(lengths_mode*capacities_mode)

    for PTsMode, PTsModesName in zip(PTsModes, PTsModesNames):
        if PTsMode != 'bike' and PTsMode != 'walk':
            lengths_mode = lengths[PTsModeIndexes[PTsMode]]
            passengers_mode = passengers[PTsModeIndexes[PTsMode]] 
            capacities_mode = capacities[PTsModeIndexes[PTsMode]] 
            if np.sum(lengths_mode*capacities_mode)>0:
                ST.at['Vehicle Load Factor '+PTsModesName, name] = np.sum(lengths_mode*passengers_mode)/np.sum(lengths_mode*capacities_mode)

#----------Ridership
    print('Ridership Transit...',name)
    total_rs = 0
    for company in transitCompanies:
        ridership_company = len(PEVs['vehicle'][(PEVs['vehicle2'] == company)])
        ST.at['Ridership '+company, name] = ridership_company
        total_rs += ridership_company
    for company in transitCompanies:
        ridership_company = len(PEVs['vehicle'][(PEVs['vehicle2'] == company)])
        ST.at['Ridership '+company+' Share', name] = ridership_company/total_rs

################################################################################################
# ################################################EXTRA FOR NYC################################################
    if is_NYC:
        PEVs_NJ = PEVs[(PEVs['vehicle2'] == 'NJ_Transit')]
        NJ_ridership_bus = 0
        NJ_ridership_rail = 0
        NJ_ridership_lrail = 0
        GTFS_NJ_RAIL_trips = pd.read_csv('GTFS/trips.txt')
        for NJ_vehicle in PEVs_NJ['vehicle']:
            if NJ_vehicle[:12] == 'NJ_Transit_B':
                NJ_ridership_bus += 1
            elif NJ_vehicle[:12] == 'NJ_Transit_R':
                trip_id = NJ_vehicle.split(':')[1]
                route_id = list(GTFS_NJ_RAIL_trips['route_id'][GTFS_NJ_RAIL_trips['trip_id'].astype(str)==trip_id])[0]
                if route_id in [4,12,16]:
                    NJ_ridership_lrail += 1
                else:
                    NJ_ridership_rail +=1

        ST.at['Ridership NJ Transit Bus', name] = NJ_ridership_bus
        ST.at['Ridership NJ Transit Rail', name] = NJ_ridership_rail
        ST.at['Ridership NJ Transit Light Rail', name] = NJ_ridership_lrail
        ST.at['Ridership NJ Transit Bus Share', name] = NJ_ridership_bus/total_rs
        ST.at['Ridership NJ Transit Rail Share', name] = NJ_ridership_rail/total_rs
        ST.at['Ridership NJ Transit Light Rail Share', name] = NJ_ridership_lrail/total_rs
        
    #Check ridership Subway

        agencies = []
        for vehicle in PtoPTss['vehicleID']:
            agencies.append(vehicle[:20])
        PtoPTss['agency'] = agencies

        grouping = PtoPTss.groupby(['personID','planIndex']).apply(lambda x: [y[0] for y in groupby(x.agency)]).to_dict()
        

        combinations = []
        i = 0
        for key in grouping.keys():
            i+=1
            combinations.append(grouping[key])

        ridership_Subway = 0
        for comb in combinations:
            for c in comb:
                if c[:10] =='NYC_Subway':
                    ridership_Subway += 1

        ST.at['Ridership NYC Subway Without Transfers', name] = ridership_Subway


################################################################################################
################################################################################################

    ST.at['Ridership Total', name] = total_rs
    ST.at['Transit Transfer per trip AV', name] = total_rs/transit_exec-1.
#----------RH
    start_RH = time.time()
    print('Ride hail...',name)

    PTsRH = PTs[PTs['isRH']]
    ST.at['Empty Trips RH', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']==0])
    ST.at['Not Empty Trips RH', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']>0])
    if len(PTsRH['vehicle']) >0:
        ST.at['Empty Trips RH Share', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']==0])/len(PTsRH['vehicle'])
        ST.at['Not Empty Trips RH Share', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']>0])/len(PTsRH['vehicle'])
    
    for code in codes:
        ST.at[CtV[code]+' Trips per RH Vehicle', name] = DA(PTsRH['vehicle'].value_counts(), code)
        
    rh_vehicles = pd.unique(PTsRH['vehicle'])
    n_empty = []
    n_notempty = []
    first_trip = []
    last_trip = []
    for rh_vehicle in rh_vehicles:
        PTs_rh_vehicle = PTsRH[PTsRH['vehicle']==rh_vehicle]
        n_empty.append(len(PTs_rh_vehicle['vehicle'][PTs_rh_vehicle['numPassengers']==0]))
        n_notempty.append(len(PTs_rh_vehicle['vehicle'][PTs_rh_vehicle['numPassengers']>0]))
        PTs_rh_vehicle = PTs_rh_vehicle.sort_values(by='time', ascending=True)
        first_trip.append(list(PTs_rh_vehicle['numPassengers'])[0])
        last_trip.append(list(PTs_rh_vehicle['numPassengers'])[-1])
        
    share_empty = np.array(n_empty)-np.array(n_notempty)
    for code in codes:
        ST.at[CtV[code]+' RH Vehicle (Empty - not Empty) Trips', name] = DA(share_empty, code)
    
    if len(first_trip) >0:
        ST.at['RH Empty Share Firts Trip', name] = np.count_nonzero(np.array(first_trip) == 0)/len(first_trip)
        ST.at['RH Empty Share Last Trip', name] = np.count_nonzero(np.array(last_trip) == 0)/len(first_trip)
        ST.at['RH not Empty Share Firts Trip', name] = np.count_nonzero(np.array(first_trip) == 1)/len(first_trip)
        ST.at['RH not Empty Share Last Trip', name] = np.count_nonzero(np.array(last_trip) == 1)/len(first_trip)

#----------RH - WC
    if is_WC == True:
        start_RH = time.time()
        print('Ride hail WC...',name)

        PTsRH = PTs[PTs['isRH_WC']]
        ST.at['Empty Trips RH WC', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']==0])
        ST.at['Not Empty Trips RH WC', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']>0])
        if len(PTsRH['vehicle']) >0:
            ST.at['Empty Trips RH WC Share', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']==0])/len(PTsRH['vehicle'])
            ST.at['Not Empty Trips RH WC Share', name] = len(PTsRH['vehicle'][PTsRH['numPassengers']>0])/len(PTsRH['vehicle'])

        for code in codes:
            ST.at[CtV[code]+' Trips per RH WC Vehicle', name] = DA(PTsRH['vehicle'].value_counts(), code)

        rh_vehicles = pd.unique(PTsRH['vehicle'])
        n_empty = []
        n_notempty = []
        first_trip = []
        last_trip = []
        for rh_vehicle in rh_vehicles:
            PTs_rh_vehicle = PTsRH[PTsRH['vehicle']==rh_vehicle]
            n_empty.append(len(PTs_rh_vehicle['vehicle'][PTs_rh_vehicle['numPassengers']==0]))
            n_notempty.append(len(PTs_rh_vehicle['vehicle'][PTs_rh_vehicle['numPassengers']>0]))
            PTs_rh_vehicle = PTs_rh_vehicle.sort_values(by='time', ascending=True)
            first_trip.append(list(PTs_rh_vehicle['numPassengers'])[0])
            last_trip.append(list(PTs_rh_vehicle['numPassengers'])[-1])

        share_empty = np.array(n_empty)-np.array(n_notempty)
        for code in codes:
            ST.at[CtV[code]+' RH WC Vehicle (Empty - not Empty) Trips', name] = DA(share_empty, code)

        if len(first_trip) >0:
            ST.at['RH WC Empty Share Firts Trip', name] = np.count_nonzero(np.array(first_trip) == 0)/len(first_trip)
            ST.at['RH WC Empty Share Last Trip', name] = np.count_nonzero(np.array(last_trip) == 0)/len(first_trip)
            ST.at['RH WC not Empty Share Firts Trip', name] = np.count_nonzero(np.array(first_trip) == 1)/len(first_trip)
            ST.at['RH WC not Empty Share Last Trip', name] = np.count_nonzero(np.array(last_trip) == 1)/len(first_trip)
    
    return ST


# In[ ]:


ST = pd.DataFrame()

for data_name, name, plan_name in zip(data_names, names, plan_names):
    
    MCs = []
    PTs = []
    PEVs = []
    PLVs = []
    MCs, PTs, PEVs, PLVs, RPs  = readEvents(fp+data_name)
    MCs, PTs, PEVs, PLVs = fixData(MCs, PTs, PEVs, PLVs, len_id_transit)
    trips, activities, personToTripDepartures, is_leg_mode = processPlans(fp+plan_name)
    PtoPTss = personToPathTraversal(PTs,PEVs,PLVs,personToTripDepartures)
    codes = [0,1,2,3,4,5,6,7,8] 
    transitCompanies = PTs['vehicle2'][PTs['is_transit']>0].value_counts().keys()
    
    ST = SummaryTable(ST, data_name, name, plan_name, MCs, PTs, PEVs, PLVs, RPs, trips, PtoPTss, codes, transitCompanies)
    
    print('Total Time', time.time()-start)
    start = time.time()
    print(ST[-6:],'Number of attributes',len(ST))
    ST.to_csv(fp_res+output_nm)
    
ST['code'] = range(len(ST[ST.keys()[0]]))
print(ST[-6:],'Number of attributes',len(ST))
ST.to_csv(fp_res+output_nm)

end = time.time()
print('Total time',end- start, 's')


# In[ ]:





# In[ ]:


#Validation

ST2 = pd.DataFrame()
# ST = pd.read_csv('outputs/SummaryTable_NYC_baseline.csv')
shares_pop = [
                # 0.33, 0.33,0.33,
                0.0295,0.0295,
    # 0.0295,0.0295,0.0295,0.0295,0.0295,
                # 0.04425,0.04425,0.04425,
                    # 0.1,0.1,
                # 0.1,0.1,0.1,0.1,0.1,
                # 0.1,0.1,0.1
                ]


for name, share_pop in zip(names,shares_pop):
    
    ST2.at['Original Population share', name] = share_pop
    
    ST2.at['Scaled Total Simulated Agents', name] = ST.at['Simulated Agents ', name]/share_pop
    

    ST2.at['Total Trips Estimated per Agent in a Day', name] = ST.at['Trips per Agent AV ', name]


    ST2.at['Scaled Total Trips Estimated in a Day', name] = ST.at['Trips per Agent AV ', name]*ST.at['Simulated Agents ', name]/share_pop

    if is_leg_mode:

        ST2.at['Scaled Total Estimated Walk-Transit Trips in a Day', name] = ST.at['Trip Est Walk-Transit', name]/share_pop


    ST2.at['Scaled Total Replanned Walk-Transit Trips in a Day', name] = ST.at['Trip Replan Walk-Transit', name]/share_pop


    ST2.at['Scaled Total Executed Walk-Transit Trips in a Day', name] = ST.at['Trip Exec Walk-Transit', name]/share_pop


    ST2.at['Scaled Total Modechoice Walk-Transit Trips in a Day', name] = ST.at['Trip Mode Walk-Transit', name]/share_pop


    ST2.at['Share Estimated Walk-Transit Trips in a Day', name] = ST.at['Trip Est Share Walk-Transit', name]


    ST2.at['Share Replanned Walk-Transit Trips in a Day', name] = ST.at['Trip Replan Share Walk-Transit', name]


    ST2.at['Share Executed Walk-Transit Trips in a Day', name] = ST.at['Trip Exec Share Walk-Transit', name]
    ST2.at['Share Executed Bike-Transit Trips in a Day', name] = ST.at['Trip Exec Share Bike-Transit', name]
    ST2.at['Share Executed Ride Hail-Transit Trips in a Day', name] = ST.at['Trip Exec Share Ride Hail-Transit', name]
    ST2.at['Share Executed Drive-Transit Trips in a Day', name] = ST.at['Trip Exec Share Drive-Transit', name]
    ST2.at['Share Executed Transit Related Trips in a Day', name] = (ST.at['Trip Exec Share Walk-Transit', name] +
                                                                    ST.at['Trip Exec Share Bike-Transit', name] +
                                                                    ST.at['Trip Exec Share Ride Hail-Transit', name] +
                                                                    ST.at['Trip Exec Share Drive-Transit', name])
    ST2.at['Share Executed Bike Trips in a Day', name] = ST.at['Trip Exec Share Bike', name]
    ST2.at['Share Executed Car Trips in a Day', name] = ST.at['Trip Exec Share Car', name]
    ST2.at['Share Executed Ride Hail Trips in a Day', name] = (ST.at['Trip Exec Share Ride Hail', name] +
                                                            ST.at['Trip Exec Share Ride Hail Pooled', name])
    ST2.at['Share Executed Walk Trips in a Day', name] = ST.at['Trip Exec Share Walk', name]
    ST2.at['Share Executed Other Trips in a Day', name] = 1-(ST.at['Trip Exec Share Walk-Transit', name] +
                                                                    ST.at['Trip Exec Share Bike-Transit', name] +
                                                                    ST.at['Trip Exec Share Ride Hail-Transit', name] +
                                                                    ST.at['Trip Exec Share Drive-Transit', name] +
                                                                    ST.at['Trip Exec Share Bike', name] +
                                                                    ST.at['Trip Exec Share Car', name] +
                                                                    ST.at['Trip Exec Share Ride Hail', name] +
                                                                    ST.at['Trip Exec Share Ride Hail Pooled', name] +
                                                                    ST.at['Trip Exec Share Walk', name])


    ST2.at['Share Executed Walk-Transit Trips in a Day', name] = ST.at['Trip Exec Share Walk-Transit', name]


    ST2.at['AV Transit Transfers per trip', name] = ST.at['Transit Transfer per trip AV', name]


    ST2.at['Scaled MTA BUS Ridership (with transfers)', name] = (ST.at['Ridership MTA_Brookl', name]+
                                                             ST.at['Ridership MTA_Bronx_', name]+
                                                             ST.at['Ridership MTA_Queens', name]+
                                                             ST.at['Ridership MTA_Staten', name]+
                                                             ST.at['Ridership MTA_Manhat', name]+
                                                             ST.at['Ridership NYC_Bus_Co', name])/share_pop


    ST2.at['Scaled MTA SUB Ridership (with transfers)', name] = ST.at['Ridership NYC_Subway', name]/share_pop
    
    ST2.at['Scaled MTA SUB Ridership (without transfers)', name] = ST.at['Ridership NYC Subway Without Transfers', name]/share_pop
    
    ST2.at['Subway vs Bus', name] = ST2.at['Scaled MTA SUB Ridership (without transfers)', name]/ST2.at['Scaled MTA BUS Ridership (with transfers)', name]


    ST2.at['Scaled Metro North Ridership (with transfers)', name] = ST.at['Ridership Metro-Nort', name]/share_pop


    ST2.at['Scaled LIRR Ridership (with transfers)', name] = ST.at['Ridership Long_Islan', name]/share_pop
    
    ST2.at['Scaled PATH Ridership (with transfers)', name] = ST.at['Ridership 151_631:t_', name]/share_pop
    
    ST2.at['Scaled NJ BUS Ridership (with transfers)', name] = ST.at['Ridership NJ Transit Bus', name]/share_pop
    ST2.at['Scaled NJ RAIL Ridership (with transfers)', name] = ST.at['Ridership NJ Transit Rail', name]/share_pop
    ST2.at['Scaled NJ LIGHT RAIL Ridership (with transfers)', name] = ST.at['Ridership NJ Transit Light Rail', name]/share_pop
    
    
ST2.at['Share Executed Transit Related Trips in a Day', 'Target'] = 'NHTS is 16.6%'
ST2.at['Share Executed Bike Trips in a Day', 'Target'] = 'NHTS is 1.0%'
ST2.at['Share Executed Car Trips in a Day', 'Target'] = 'NHTS is 53.9%'
ST2.at['Share Executed Walk Trips in a Day', 'Target'] = 'NHTS is 26.1%'
ST2.at['Share Executed Ride Hail Trips in a Day', 'Target'] = 'NHTS is 1.8%'
ST2.at['Share Executed Other Trips in a Day', 'Target'] = 'NHTS is 0.6%'

ST2.at['Scaled MTA BUS Ridership (with transfers)', 'Target'] = '≈2.2M'
ST2.at['Scaled MTA SUB Ridership (without transfers)', 'Target'] = '≈5.4M'
ST2.at['Subway vs Bus', 'Target'] = '≈2.5'
ST2.at['Scaled Metro North Ridership (with transfers)', 'Target'] = '≈0.2M'
ST2.at['Scaled LIRR Ridership (with transfers)', 'Target'] = '≈0.25M'
    
ST2.at['Scaled PATH Ridership (with transfers)', 'Target'] = '297k'
ST2.at['Scaled NJ BUS Ridership (with transfers)', 'Target'] =  '451k'
ST2.at['Scaled NJ RAIL Ridership (with transfers)', 'Target'] =  '127k'
ST2.at['Scaled NJ LIGHT RAIL Ridership (with transfers)', 'Target'] =  '14k'

    
ST2.to_csv('outputs/validationNYC.csv')


# In[ ]:





# In[ ]:





# In[ ]:




