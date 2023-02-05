from pandas import read_csv


def beam_npmrds_screeline_validation(link_stats_file, output_dir, percent_of_samples):
    modeled_vmt = read_csv(link_stats_file, low_memory=False)
    hourly_vol_to_check = modeled_vmt.groupby('hour')[['volume']].sum()
    model_vmt_24_hour = modeled_vmt.loc[(modeled_vmt['hour'] <= 28) & (modeled_vmt['hour'] >= 5)]
    model_vmt_24_hour.loc[model_vmt_24_hour['hour']>=24, 'hour'] -= 24
    model_network.loc[:, 'fromNodeId'] = model_network.loc[:, 'fromNodeId'].astype(int)
    model_network.loc[:, 'toNodeId'] = model_network.loc[:, 'toNodeId'].astype(int)
    model_vmt_24_hour = pd.merge(model_vmt_24_hour, model_network, left_on = ['link', 'from', 'to'], right_on = ['linkId', 'fromNodeId', 'toNodeId'], how = 'inner')
    demand_scaling = 1/percent_of_samples
    model_vmt_24_hour.loc[:, 'combined_volume'] = model_vmt_24_hour.loc[:, 'volume'] * demand_scaling + model_vmt_24_hour.loc[:, 'TruckVolume']  # activate this for freight runs
    model_vmt_24_hour.loc[:, 'hourly volume'] = model_vmt_24_hour.loc[:, 'volume']/model_vmt_24_hour.loc[:, 'numberOfLanes']
    model_vmt_24_hour.loc[:, 'VMT'] = demand_scaling * meter_to_mile * model_vmt_24_hour.loc[:, 'linkLength'] * model_vmt_24_hour.loc[:, 'combined_volume']
    model_vmt_24_hour.loc[:, 'speed'] = model_vmt_24_hour.loc[:, 'linkLength'] / model_vmt_24_hour.loc[:, 'traveltime'] 
    model_vmt_24_hour.loc[:, 'speed (mph)'] = mps_to_mph * model_vmt_24_hour.loc[:, 'speed']
    model_vmt_24_hour["speed (mph)"].plot(kind="hist", weights=model_vmt_24_hour["combined_volume"], bins = 30)
    plt.xlabel('Hourly speed (mph)')
    plt.savefig(output_dir + '/modeled_speed_distribution.png', dpi = 200)

    model_vmt_hour_volume = model_vmt_24_hour.groupby(['Tmc', 'hour'])[['hourly volume', 'VMT']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['Tmc', 'hour', 'Volume (veh/lane/hour)', 'VMT']
    model_vmt_24_hour_filtered = model_vmt_24_hour.loc[model_vmt_24_hour['volume']>0]

    model_vmt_hour_speed = model_vmt_24_hour_filtered.groupby(['Tmc', 'hour']).apply(lambda x: np.average(x.speed, weights=x.combined_volume))
    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['Tmc', 'hour', 'Avg.Speed (mph)'] 
    model_vmt_hour_speed.loc[:, 'Avg.Speed (mph)'] *= mps_to_mph

    model_vmt_hour_data = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on = ['Tmc', 'hour'], how = 'left')
    sns.lineplot(x = 'hour', y = 'Avg.Speed (mph)', data = model_vmt_hour_data, ci = 95)
    plt.ylim([0, 70])
    plt.savefig(output_dir + '/modeled_speed_NPMRDS_screenline.png', dpi = 200)
    plt.show()

    sns.lineplot(x = 'hour', y = 'Volume (veh/lane/hour)', data = model_vmt_hour_data, ci = 95)
    # plt.ylim([0, 70])
    # plt.ylabel('volume (veh/lane/hour)')
    plt.savefig(output_dir + '/modeled_volume_NPMRDS_screenline.png', dpi = 200)
    plt.show()
    
    # compare two datasets
    SF_NPMRDS_data_hourly_speed.loc[:, 'source'] = 'NPMRDS'
    model_vmt_hour_data.loc[:, 'source'] = 'BEAM output'
    combined_data = pd.concat([SF_NPMRDS_data_hourly_speed, model_vmt_hour_data])
    combined_data = combined_data.reset_index()
    
    sns.lineplot(x = 'hour', y = 'Avg.Speed (mph)', hue = 'source', data = combined_data, ci = 95)
    plt.ylim([0, 70])
    plt.savefig(output_dir + '/BEAM_NPMRDS_screenline_speed_validation.png', dpi = 200)
    plt.show()
