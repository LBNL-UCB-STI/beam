import pandas as pd
from pandas import read_csv
import seaborn as sns
import matplotlib.pyplot as plt
plt.style.use('ggplot')
import numpy as np

meter_to_mile = 0.000621371
mps_to_mph = 2.23694


def npmrds_screeline_validation(npmrds_data, model_network, output_dir, label, show_plots = False, assume_daylight_savings = False):
    list_of_tmcs = model_network.loc[:, 'Tmc'].unique()
    npmrds_data = npmrds_data.loc[npmrds_data['tmc_code'].isin(list_of_tmcs)]
    npmrds_data.loc[:, 'formatted_time'] = pd.to_datetime(npmrds_data.loc[:, 'measurement_tstamp'], format="%Y-%m-%d %H:%M:%S")
    npmrds_data.loc[:, 'weekday'] = npmrds_data.loc[:, 'formatted_time'].dt.weekday
    npmrds_data.loc[:, 'hour'] = npmrds_data.loc[:, 'formatted_time'].dt.hour
    if assume_daylight_savings:
        npmrds_data.loc[:, 'hour'] = (npmrds_data.loc[:, 'hour'] - 1) % 24
        
    npmrds_data = npmrds_data.loc[npmrds_data['weekday'] == 1]

    npmrds_data.loc[npmrds_data['speed']>= 80, 'speed'] = 80
    npmrds_data_hourly = npmrds_data.groupby(['tmc_code', 'hour'])[['speed']].mean()
    npmrds_data_hourly = npmrds_data_hourly.reset_index()
    npmrds_data_hourly.columns = ['Tmc', 'hour', 'Avg.Speed (mph)']

    sns.lineplot(data = npmrds_data_hourly, x = "hour", y = "Avg.Speed (mph)", ci=95)
    plt.ylim([0, 70])
    plt.ylabel('average speed (mph)')
    plt.savefig(output_dir + '/NPMRDS_hourly_mean_speed.png', bbox_inches='tight', dpi = 300)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    npmrds_data_hourly.loc[:, 'source'] = label
    return npmrds_data_hourly


def build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, label, passenger_sample_fraction, freight_sample_fraction, show_plots = False):
    hourly_vol_to_check = modeled_vmt.groupby('hour')[['volume']].sum()
    model_vmt_24_hour = modeled_vmt.loc[(modeled_vmt['hour'] <= 28) & (modeled_vmt['hour'] >= 5)]
    model_vmt_24_hour.loc[model_vmt_24_hour['hour']>=24, 'hour'] -= 24
    model_network.loc[:, 'fromNodeId'] = model_network.loc[:, 'fromNodeId'].astype(int)
    model_network.loc[:, 'toNodeId'] = model_network.loc[:, 'toNodeId'].astype(int)
    model_vmt_24_hour = pd.merge(model_vmt_24_hour, model_network, left_on = ['link', 'from', 'to'], right_on = ['linkId', 'fromNodeId', 'toNodeId'], how = 'inner')
    demand_scaling = 1/passenger_sample_fraction
    freight_scaling = 1/freight_sample_fraction
    # activate this for freight runs
    model_vmt_24_hour.loc[:, 'combined_volume'] = model_vmt_24_hour.loc[:, 'volume'] * demand_scaling
    if 'TruckVolume' in model_vmt_24_hour.columns:
        model_vmt_24_hour.loc[:, 'combined_volume'] = model_vmt_24_hour.loc[:, 'combined_volume'] + model_vmt_24_hour.loc[:, 'TruckVolume'] * freight_scaling
    model_vmt_24_hour.loc[:, 'hourly volume'] = model_vmt_24_hour.loc[:, 'volume']/model_vmt_24_hour.loc[:, 'numberOfLanes']
    model_vmt_24_hour.loc[:, 'VMT'] = demand_scaling * meter_to_mile * model_vmt_24_hour.loc[:, 'linkLength'] * model_vmt_24_hour.loc[:, 'combined_volume']
    # model_vmt_24_hour.loc[:, 'travel_time (hr)'] = model_vmt_24_hour.loc[:, 'linkLength'] /3600
    model_vmt_24_hour.loc[:, 'speed'] = model_vmt_24_hour.loc[:, 'linkLength'] / model_vmt_24_hour.loc[:, 'traveltime']
    model_vmt_24_hour.loc[:, 'speed (mph)'] = mps_to_mph * model_vmt_24_hour.loc[:, 'speed']

    model_vmt_24_hour["speed (mph)"].plot(kind="hist", weights=model_vmt_24_hour["combined_volume"], bins = 30)
    plt.xlabel('Hourly speed (mph)')
    plt.savefig(output_dir + '/modeled_speed_distribution.png', dpi = 200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    return model_vmt_24_hour


def beam_screeline_validation(modeled_vmt, model_network, output_dir, label, passenger_sample_fraction, freight_sample_fraction, show_plots = False, assume_daylight_savings = False):
    model_vmt_24_hour = build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, label, passenger_sample_fraction, freight_sample_fraction, show_plots)
    model_vmt_hour_volume = model_vmt_24_hour.groupby(['Tmc', 'hour'])[['hourly volume', 'VMT']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['Tmc', 'hour', 'Volume (veh/lane/hour)', 'VMT']
    model_vmt_24_hour_filtered = model_vmt_24_hour.loc[model_vmt_24_hour['volume']>0]

    model_vmt_hour_speed = model_vmt_24_hour_filtered.groupby(['Tmc', 'hour']).apply(lambda x: np.average(x.speed, weights=x.combined_volume))
    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['Tmc', 'hour', 'Avg.Speed (mph)']
    model_vmt_hour_speed.loc[:, 'Avg.Speed (mph)'] *= mps_to_mph

    beam_data_hourly = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on = ['Tmc', 'hour'], how = 'left')
    
    if assume_daylight_savings:
        beam_data_hourly.loc[:, 'hour'] = (beam_data_hourly.loc[:, 'hour'] - 1) % 24
    
    sns.lineplot(x = 'hour', y = 'Avg.Speed (mph)', data = beam_data_hourly, ci = 95)
    plt.ylim([0, 70])
    plt.savefig(output_dir + '/modeled_speed_NPMRDS_screenline.png', dpi = 200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    sns.lineplot(x = 'hour', y = 'Volume (veh/lane/hour)', data = beam_data_hourly, ci = 95)
    # plt.ylim([0, 70])
    # plt.ylabel('volume (veh/lane/hour)')
    plt.savefig(output_dir + '/modeled_volume_NPMRDS_screenline.png', dpi = 200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    # compare two datasets
    beam_data_hourly.loc[:, 'source'] = label
    return beam_data_hourly


def beam_screeline_validation_per_road_class(npmrds_data_hourly_speed, modeled_vmt, model_network, output_dir, label, passenger_sample_fraction, freight_sample_fraction, show_plots = False, assume_daylight_savings = False):
    model_vmt_24_hour = build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, label, passenger_sample_fraction, freight_sample_fraction, show_plots)
    model_vmt_hour_volume = model_vmt_24_hour.groupby(['Tmc', 'hour'])[['hourly volume', 'VMT']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['Tmc', 'hour', 'Volume (veh/lane/hour)', 'VMT']
    model_vmt_24_hour_filtered = model_vmt_24_hour.loc[model_vmt_24_hour['volume']>0]

    model_vmt_hour_speed = model_vmt_24_hour_filtered.groupby(['Tmc', 'hour']).apply(lambda x: np.average(x.speed, weights=x.combined_volume))
    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['Tmc', 'hour', 'Avg.Speed (mph)']
    model_vmt_hour_speed.loc[:, 'Avg.Speed (mph)'] *= mps_to_mph

    model_network = model_network.drop_duplicates(subset=['linkId'])
    modeled_roadtype_lookup = {'tertiary': 'Minor collector',
                               'trunk_link': 'Freeway and major arterial',
                               'residential': 'Local',
                               'track': 'Local',
                               'footway': 'Local',
                               'motorway': 'Freeway and major arterial',
                               'secondary': 'Major collector',
                               'unclassified': 'Local',
                               'path': 'Local',
                               'secondary_link': 'Major collector',
                               'primary': 'Minor arterial',
                               'motorway_link': 'Freeway and major arterial',
                               'primary_link': 'Minor arterial',
                               'trunk': 'Freeway and major arterial',
                               'pedestrian': 'Local',
                               'tertiary_link': 'Minor collector',
                               'cycleway': 'Local',
                               np.nan: 'Local',
                               'steps': 'Local',
                               'living_street': 'Local',
                               'bus_stop': 'Local',
                               'corridor': 'Local',
                               'road': 'Local',
                               'bridleway': 'Local'}

    model_network.loc[:, 'road_class'] = model_network.loc[:, 'attributeOrigType'].map(modeled_roadtype_lookup)
    tmc_county_lookup = model_network.loc[:, ['NAME', 'Tmc', 'road_class']]
    tmc_county_lookup = tmc_county_lookup.drop_duplicates(subset=['Tmc'])

    model_vmt_hour_data = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on = ['Tmc', 'hour'], how = 'left')
    paired_data_for_comparison = pd.merge(npmrds_data_hourly_speed, model_vmt_hour_data, on = ['Tmc', 'hour'], how = 'left')
    paired_data_for_comparison = pd.merge(paired_data_for_comparison, tmc_county_lookup, on = 'Tmc', how = 'left')
    paired_data_for_comparison = paired_data_for_comparison.rename(columns = {'Avg.Speed (mph)_x': 'SF NPMRDS speed', 'Avg.Speed (mph)_y': 'BEAM speed'})
    paired_data_for_comparison = paired_data_for_comparison.dropna(subset = ['BEAM speed'])
    # paired_data_for_comparison["KITS ID"] = paired_data_for_comparison["KITS ID"].astype(str)
    # paired_data_for_comparison.head(5)
    paired_data_melt = pd.melt(paired_data_for_comparison,
                                id_vars = ['Tmc', 'hour', 'road_class'],
                                value_vars = ["SF NPMRDS speed", "BEAM speed"],
                                var_name = 'source',
                                value_name = 'speed (mph)')
    paired_data_melt = paired_data_melt.reset_index()
    paired_data_melt = paired_data_melt.sort_values('road_class')
    paired_data_melt.loc[:, 'source'] = label

    VMT_by_hour = paired_data_for_comparison.groupby(['hour', 'road_class'])[['VMT']].sum()
    VMT_by_hour = VMT_by_hour.reset_index()
    VMT_by_hour.loc[:, 'source'] = label
    
    if assume_daylight_savings:
        VMT_by_hour.loc[:, 'hour'] = (VMT_by_hour.loc[:, 'hour'] - 1) % 24
        
    return VMT_by_hour
