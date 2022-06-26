#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[20]:


import urllib
from urllib.error import HTTPError

def get_output_path_from_s3_url(s3_url):
    """
    transform s3 output path (from beam runs spreadsheet) into path to s3 output
    that may be used as part of path to the file.
    s3path = get_output_path_from_s3_url(s3url)
    beam_log_path = s3path + '/beamLog.out'
    """

    return s3_url         .strip()         .replace("s3.us-east-2.amazonaws.com/beam-outputs/index.html#", "beam-outputs.s3.amazonaws.com/")


def parse_config(s3url, complain=True):
    """
    parse beam config of beam run.

    :param s3url: url to s3 output
    :param complain: it will complain if there are many config values found with the same name
    :return: dictionary config key -> config value
    """

    s3path = get_output_path_from_s3_url(s3url)
    url = s3path + "/fullBeamConfig.conf"
    config = urllib.request.urlopen(url)

    config_keys = ["flowCapacityFactor", "speedScalingFactor", "quick_fix_minCarSpeedInMetersPerSecond",
                   "activitySimEnabled", "transitCapacity", "folder",
                   "minimumRoadSpeedInMetersPerSecond", "fractionOfInitialVehicleFleet",
                   "agentSampleSizeAsFractionOfPopulation",
                   "simulationName", "directory", "generate_secondary_activities", "lastIteration",
                   "fractionOfPeopleWithBicycle",
                   "parkingStallCountScalingFactor", "parkingPriceMultiplier", "parkingCostScalingFactor", "queryDate",
                   "transitPrice", "transit_crowding", "transit_crowding_percentile",
                   "maxLinkLengthToApplySpeedScalingFactor", "max_destination_distance_meters",
                   "max_destination_choice_set_size",
                   "transit_crowding_VOT_multiplier", "transit_crowding_VOT_threshold",
                   "activity_file_path", "intercept_file_path", "additional_trip_utility",
                   "ModuleProbability_1", "ModuleProbability_2", "ModuleProbability_3", "ModuleProbability_4",
                   "BUS-DEFAULT", "RAIL-DEFAULT", "SUBWAY-DEFAULT"]
    intercept_keys = ["bike_intercept", "car_intercept", "drive_transit_intercept", "ride_hail_intercept",
                      "ride_hail_pooled_intercept", "ride_hail_transit_intercept", "walk_intercept",
                      "walk_transit_intercept", "transfer"]

    config_map = {}
    default_value = ""

    for conf_key in config_keys:
        config_map[conf_key] = default_value

    def set_value(key, line_value):
        value = line_value.strip().replace("\"", "")

        if key not in config_map:
            config_map[key] = value
        else:
            old_val = config_map[key]
            if old_val == default_value or old_val.strip() == value.strip():
                config_map[key] = value
            else:
                if complain:
                    print("an attempt to rewrite config value with key:", key)
                    print("   value in the map  \t", old_val)
                    print("   new rejected value\t", value)

    physsim_names = ['JDEQSim', 'BPRSim', 'PARBPRSim', 'CCHRoutingAssignment']

    def look_for_physsim_type(config_line):
        for physsim_name in physsim_names:
            if 'name={}'.format(physsim_name) in config_line:
                set_value("physsim_type", "physsim_type = {}".format(physsim_name))

    for b_line in config.readlines():
        line = b_line.decode("utf-8").strip()

        look_for_physsim_type(line)

        for ckey in config_keys:
            if ckey + "=" in line or ckey + "\"=" in line or '"' + ckey + ":" in line:
                set_value(ckey, line)

        for ikey in intercept_keys:
            if ikey in line:
                set_value(ikey, line)

    return config_map


# In[21]:


s3urls = [
    "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/newyork/NYC-doublerouter-baseline-bus-vs-subway-2__2020-10-07_17-00-02_bvy",
    "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/newyork/nyc-200k--85-04-5__2020-10-07_14-40-29_hdb",
    "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/newyork/nyc-200k--65-09-5__2020-10-07_14-40-50_hrd",
    "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/newyork/nyc-200k-future-return_to_normal-5-bus_05-work_low-fear_med-triputility--5__2020-10-17_11-52-56_yxz"
]

url2config = {}

for s3url in s3urls:
    config_map = parse_config(s3url, False)
    url2config[s3url] = config_map

for s3url in url2config:
    config_map = url2config[s3url]
    print(config_map.get("simulationName","??"))
    print(config_map.get("folder","??"))
    print(config_map.get("agentSampleSizeAsFractionOfPopulation","??"))
    


# In[ ]:





# In[ ]:





# In[ ]:




