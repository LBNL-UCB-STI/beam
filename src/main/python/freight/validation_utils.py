import pandas as pd
from pandas import read_csv
import seaborn as sns
import matplotlib.pyplot as plt
plt.style.use('ggplot')
import numpy as np

meter_to_mile = 0.000621371
mps_to_mph = 2.23694
seconds_to_hours = 1/3600.0


def npmrds_screeline_validation(npmrds_data, model_network, output_dir, label, show_plots=False):
    npmrds_data = npmrds_data.loc[npmrds_data['tmc_code'].isin(model_network.loc[:, 'Tmc'].unique())]
    npmrds_data.loc[:, 'formatted_time'] = pd.to_datetime(npmrds_data.loc[:, 'measurement_tstamp'], format="%Y-%m-%d %H:%M:%S")
    npmrds_data.loc[:, 'weekday'] = npmrds_data.loc[:, 'formatted_time'].dt.weekday
    npmrds_data.loc[:, 'hour'] = npmrds_data.loc[:, 'formatted_time'].dt.hour % 24

    # 0: Monday => 6: Sunday
    npmrds_data = npmrds_data.loc[npmrds_data['weekday'] < 5]

    # npmrds_data.loc[npmrds_data['speed'] >= 80, 'speed'] = 80
    npmrds_data_hourly = npmrds_data.groupby(['tmc_code', 'hour'])[['speed']].mean()
    npmrds_data_hourly = npmrds_data_hourly.reset_index()
    npmrds_data_hourly.columns = ['tmc', 'hour', 'avgSpeed']

    sns.lineplot(data=npmrds_data_hourly, x="hour", y="avgSpeed", ci=95)
    plt.ylim([0, 70])
    plt.ylabel('Average Speed (mph)')
    plt.savefig(output_dir + '/NPMRDS_hourly_mean_speed.png', bbox_inches='tight', dpi=300)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    npmrds_data_hourly.loc[:, 'scenario'] = label
    return npmrds_data_hourly


def build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, scenario, passenger_sample_fraction, freight_sample_fraction, show_plots=False, assume_daylight_savings=False):
    model_vmt_24_hour = modeled_vmt.copy()

    if assume_daylight_savings:
        model_vmt_24_hour.loc[:, 'hour'] = (model_vmt_24_hour.loc[:, 'hour'] - 1) % 24
    else:
        model_vmt_24_hour.loc[:, 'hour'] = model_vmt_24_hour.loc[:, 'hour'] % 24

    model_network.loc[:, 'fromNodeId'] = model_network.loc[:, 'fromNodeId'].astype(int)
    model_network.loc[:, 'toNodeId'] = model_network.loc[:, 'toNodeId'].astype(int)
    model_vmt_24_hour = pd.merge(model_vmt_24_hour, model_network, left_on=['link', 'from', 'to'], right_on=['linkId', 'fromNodeId', 'toNodeId'], how='inner')
    model_vmt_24_hour.rename(columns={'Tmc': 'tmc'}, inplace=True)

    demand_scaling = 1/passenger_sample_fraction
    freight_scaling = 1/freight_sample_fraction

    # NOW Volume does not contain Trucks, but in the future Trucks will be included in Volume.
    model_vmt_24_hour.loc[:, 'volume'] = model_vmt_24_hour.loc[:, 'volume'] * demand_scaling
    if 'TruckVolume' in model_vmt_24_hour.columns:
        model_vmt_24_hour.loc[:, 'volume'] = model_vmt_24_hour.loc[:, 'volume'] + (model_vmt_24_hour.loc[:, 'TruckVolume'] * freight_scaling)

    model_vmt_24_hour.loc[:, 'vmt'] = meter_to_mile * model_vmt_24_hour.loc[:, 'linkLength'] * model_vmt_24_hour.loc[:, 'volume']
    model_vmt_24_hour.loc[:, 'vht'] = seconds_to_hours * model_vmt_24_hour.loc[:, 'traveltime'] * model_vmt_24_hour.loc[:, 'volume']
    model_vmt_24_hour.loc[:, 'speed'] = model_vmt_24_hour.loc[:, 'vmt'] / model_vmt_24_hour.loc[:, 'vht']

    model_vmt_24_hour["speed"].plot(kind="hist", bins=30)
    plt.xlabel('Hourly speed (mph)')
    plt.savefig(output_dir + '/modeled_speed_distribution.png', dpi=200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    model_vmt_24_hour.loc[:, 'scenario'] = scenario
    return model_vmt_24_hour


def beam_screeline_validation(modeled_vmt, model_network, output_dir, scenario, passenger_sample_fraction, freight_sample_fraction, show_plots=False, assume_daylight_savings=False):
    model_vmt_24_hour = build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, scenario, passenger_sample_fraction, freight_sample_fraction, show_plots, assume_daylight_savings)

    model_vmt_hour_volume = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour'])[['volume', 'vmt']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['scenario', 'tmc', 'hour', 'avgVolume', 'avgVmt']

    model_vmt_hour_speed = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour']).apply(lambda x: np.sum(x.vmt)/np.sum(x.vht))
    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['scenario', 'tmc', 'hour', 'avgSpeed']

    beam_data_hourly = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on=['scenario', 'tmc', 'hour'], how='left')

    sns.lineplot(x='hour', y='avgSpeed', data=beam_data_hourly, ci=95)
    # plt.ylim([0, 70])
    plt.savefig(output_dir + '/modeled_speed_NPMRDS_screenline.png', dpi=200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    sns.lineplot(x='hour', y='avgVolume', data=beam_data_hourly, ci=95)
    # plt.ylim([0, 70])
    # plt.ylabel('volume (veh/lane/hour)')
    plt.savefig(output_dir + '/modeled_volume_NPMRDS_screenline.png', dpi=200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    return beam_data_hourly


def beam_screeline_validation_per_road_class(npmrds_data_hourly_speed, modeled_vmt, model_network, output_dir, scenario, passenger_sample_fraction, freight_sample_fraction, show_plots=False, assume_daylight_savings=False):
    model_vmt_24_hour = build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, scenario, passenger_sample_fraction, freight_sample_fraction, show_plots, assume_daylight_savings)

    model_vmt_hour_volume = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour'])[['avgVolume', 'vmt']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['scenario', 'tmc', 'hour', 'avgVolume', 'vmt']

    model_vmt_hour_speed = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour']).apply(lambda x: np.sum(x.vmt)/np.sum(x.vht))
    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['scenario', 'tmc', 'hour', 'avgSpeed']

    model_network = model_network.drop_duplicates(subset=['linkId'])
    modeled_road_type_lookup = {'tertiary': 'Minor collector',
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

    model_network.loc[:, 'road_class'] = model_network.loc[:, 'attributeOrigType'].map(modeled_road_type_lookup)
    tmc_county_lookup = model_network.loc[:, ['NAME', 'Tmc', 'road_class']]
    tmc_county_lookup = tmc_county_lookup.drop_duplicates(subset=['Tmc'])
    tmc_county_lookup.rename(columns={'Tmc': 'tmc'}, inplace=True)
    tmc_county_lookup.rename(columns={'NAME': 'name'}, inplace=True)

    model_vmt_hour_data = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on=['scenario', 'tmc', 'hour'], how='left')
    paired_data_for_comparison = pd.merge(npmrds_data_hourly_speed, model_vmt_hour_data, on=['scenario', 'tmc', 'hour'], how='left')
    paired_data_for_comparison = pd.merge(paired_data_for_comparison, tmc_county_lookup, on=['scenario', 'tmc'], how='left')
    paired_data_for_comparison = paired_data_for_comparison.rename(columns={'avgSpeed_x': 'NPMRDS_avgSpeed', 'avgSpeed_y': 'BEAM_avgSpeed'})
    paired_data_for_comparison = paired_data_for_comparison.dropna(subset=['BEAM_avgSpeed'])

    pd.melt(paired_data_for_comparison, id_vars=['scenario', 'tmc', 'hour', 'road_class'], value_vars=["NPMRDS_avgSpeed", "BEAM_avgSpeed"], var_name='scenario', value_name='speed (mph)')

    vmt_by_hour = paired_data_for_comparison.groupby(['scenario', 'hour', 'road_class'])[['vmt']].sum()
    vmt_by_hour = vmt_by_hour.reset_index()

    return vmt_by_hour
