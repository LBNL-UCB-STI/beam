import matplotlib
import sys
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import matplotlib.patches as mpatches
import matplotlib.lines as mlines
import os

plt.style.use('seaborn-colorblind')
# plt.style.use('ggplot')
plt.rcParams['axes.edgecolor'] = 'black'
plt.rcParams['axes.facecolor'] = 'white'
plt.rcParams['savefig.facecolor'] = 'white'
plt.rcParams['savefig.edgecolor'] = 'black'

colors = {'blue': '#377eb8', 'green': '#227222', 'orange': '#C66200', 'purple': '#470467', 'red': '#B30C0C',
          'yellow': '#C6A600', 'light.green': '#C0E0C0', 'magenta': '#D0339D', 'dark.blue': '#23128F',
          'brown': '#542D06', 'grey': '#8A8A8A', 'dark.grey': '#2D2D2D', 'light.yellow': '#FFE664',
          'light.purple': '#9C50C0', 'light.orange': '#FFB164', 'black': '#000000'}
mode_colors = {'RH': colors['red'],
               'Car': colors['grey'],
               'Walk': colors['green'],
               'Transit': colors['blue'],
               'RHT': colors['light.purple'],
               'RHP': 'mediumorchid',
               'CAV': colors['light.yellow'],
               'Bike': colors['light.orange'],
               'NM': colors['light.orange'],
               'electricity': colors['blue'],
               'gas': colors['purple'],
               'diesel': colors['yellow']}
angle = 0
horizontal_align = "center"

def getDfForPlt(_plt_setup3, _output_folder):
    if not os.path.exists('{}/{}'.format(_output_folder,_plt_setup3['plots_folder'])):
        os.makedirs('{}/{}'.format(_output_folder,_plt_setup3['plots_folder']))
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    years = _plt_setup3['scenarios_year']
    ids = _plt_setup3['scenarios_id']
    iterations = _plt_setup3['scenarios_itr']
    top_labels_xpos = [1]
    bottom_labels_xpos = [1]
    for i in range(1, len(top_labels)):
        top_labels_xpos.append(top_labels_xpos[i-1] + 1.2 + i % 2)
    for i in range(1, len(bottom_labels)):
        bottom_labels_xpos.append(bottom_labels_xpos[i-1] + 1.2 + i % 2)
    df = pd.DataFrame()
    for i in range(len(ids)):
        iteration = iterations[i]
        year = years[i]
        id = ids[i]
        metrics_file = "{}/{}.{}.metrics-final.csv".format(_output_folder, year, iteration)
        df_temp = pd.read_csv(metrics_file).fillna(0)
        df = pd.concat([df, df_temp[df_temp['Rank'] == id]], sort=False)
    return (df.sort_values(by=['Rank']), top_labels_xpos, bottom_labels_xpos)

# plots


def pltModeSplitByTripsInternal(_plt_setup3, _output_folder, rh_realized, filename, title):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.{}.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'], filename)
    output_csv = '{}/{}/{}.{}.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'], filename)

    createColumnIfNotExist(df, 'cav_counts', 0)

    rh_multi_passenger = df['ride_hail_pooled_counts'].values
    rh_solo_passenger = df['ride_hail_counts'].values
    if rh_realized:
        rh_multi_passenger = df['ride_hail_pooled_counts'].values * df["multi_passengers_trips_per_pool_trips"].values
        rh_solo_passenger = rh_solo_passenger + (df['ride_hail_pooled_counts'].values - rh_multi_passenger)

    data = pd.DataFrame(
        {'transit': (df['drive_transit_counts'].values + df['ride_hail_transit_counts'].values + df['walk_transit_counts'].values),
         'car': df['car_counts'].values,
         'cav': df['cav_counts'].values,
         'rh': rh_solo_passenger,
         'rhp': rh_multi_passenger,
         'walk': df['walk_counts'].values,
         'bike': df['bike_counts'].values
         })
    data = data.div(data.sum(axis=1), axis=0)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_transit = plt.bar(x=top_labels_xpos, height=data['transit'], color=mode_colors['Transit'],zorder=3)
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'],zorder=3)
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'],zorder=3)
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'],zorder=3)
    plt_rhp = plt.bar(x=top_labels_xpos, height=data['rhp'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'],zorder=3)
    plt_bike = plt.bar(x=top_labels_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp']].sum(axis=1), color=mode_colors['Bike'],zorder=3)
    plt_walk = plt.bar(x=top_labels_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp', 'bike']].sum(axis=1), color=mode_colors['Walk'],zorder=3)
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)

    height_all = data.sum(axis=1)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')

    plt.ylabel(title)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()



def pltModeSplitByTrips(_plt_setup3, _output_folder):
    pltModeSplitByTripsInternal(_plt_setup3, _output_folder, False, "modesplit_trips", "Portion of Trips")



def pltRealizedModeSplitByTrips(_plt_setup3, _output_folder):
    pltModeSplitByTripsInternal(_plt_setup3, _output_folder, True, "realized_modesplit_trips", "Portion of Realized Trips")


def createColumnIfNotExist(df, name, value):
    if name not in df.columns:
        df[name] = value
    else:
        df[name].fillna(0, inplace=True)

def tableSummary(_plt_setup3, _output_folder):
    #print(_plt_setup3)
    factor = _plt_setup3['expansion_factor']
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_csv = '{}/{}/{}.summary.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    tot_vmt_transit = (df['VMT_bus'].values+df['VMT_cable_car'].values+df['VMT_ferry'].values+df['VMT_rail'].values +
                       df['VMT_subway'].values+df['VMT_tram'].values)
    tot_vmt_non_transit = (df['VMT_car'].values+df['VMT_car_CAV'].values+df['VMT_car_RH'].values +
                           df['VMT_car_RH_CAV'].values + df['VMT_walk'].values+df['VMT_bike'].values)

    tot_ldv_vmt = (df['VMT_car'].values+df['VMT_car_CAV'].values+df['VMT_car_RH'].values + df['VMT_car_RH_CAV'].values)

    createColumnIfNotExist(df, 'personTravelTime_cav', 0)
    tot_pht = (df['personTravelTime_bike'].values+df['personTravelTime_car'].values+df['personTravelTime_cav'].values +
               df['personTravelTime_drive_transit'].values+df['personTravelTime_mixed_mode'].values +
               df['personTravelTime_onDemandRide'].values+df['personTravelTime_onDemandRide_pooled'].values +
               df['personTravelTime_onDemandRide_transit'].values+df['personTravelTime_walk'].values +
               df['personTravelTime_walk_transit'].values) * factor

    tot_energy = (df['totalEnergy_Biodiesel'].values+df['totalEnergy_Diesel'].values +
                  df['totalEnergy_Electricity'].values+df['totalEnergy_Gasoline'].values) * factor

    data = pd.DataFrame(
        {'VMT Total (10^6)': (tot_vmt_transit + tot_vmt_non_transit * factor) / 1000000,
         'VMT per Capita': (tot_vmt_transit+tot_vmt_non_transit)/df['population'],
         'VMT Light Duty Total (10^6)': tot_ldv_vmt * factor / 1000000,
         'VMT Light Duty per Capita': tot_ldv_vmt/df['population'],
         'Driving Speed [miles/h]': tot_ldv_vmt/df['total_VHT_LightDutyVehicles'],
         'Person Hours (10^6)': tot_pht / 60 / 1000000,
         'PEV (%)': 0,
         'Vehicle Energy (GJ)': tot_energy / 1000000000,
         'MEP': 0
         })
    data['Scenario'] = df['Scenario'].values.copy()
    data['Technology'] = df['Technology'].values.copy()
    data['year'] = _plt_setup3['scenarios_year']
    data.to_csv(output_csv)
    #print(data)

def pltAveragePersonSpeed_allModes(_plt_setup3, _output_folder):
    pltAverageSpeed_internal(_plt_setup3, _output_folder,'average_person_speed_all_modes','personAverageSpeedAllModes')

def pltAveragePersonSpeed_car(_plt_setup3, _output_folder):
    pltAverageSpeed_internal(_plt_setup3, _output_folder,'average_person_speed_only_car','personAverageSpeedOnlyCar')

def pltAverageLDVSpeed(_plt_setup3, _output_folder):
    pltAverageSpeed_internal(_plt_setup3, _output_folder,'average_ldv_speed','averageSpeedLDV')


def createColumnIfDoesNotExist(df):
    createColumnIfNotExist(df, 'PMT_bike', 0)
    createColumnIfNotExist(df, 'PMT_bus', 0)
    createColumnIfNotExist(df, 'PMT_cable_car', 0)
    createColumnIfNotExist(df, 'PMT_car', 0)
    createColumnIfNotExist(df, 'PMT_ferry', 0)
    createColumnIfNotExist(df, 'PMT_rail', 0)
    createColumnIfNotExist(df, 'PMT_subway', 0)
    createColumnIfNotExist(df, 'PMT_tram', 0)
    createColumnIfNotExist(df, 'PMT_walk', 0)
    createColumnIfNotExist(df, 'PMT_car_CAV', 0)
    createColumnIfNotExist(df, 'PMT_car_RH', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV', 0)

    createColumnIfNotExist(df, 'PHT_bike', 0)
    createColumnIfNotExist(df, 'PHT_bus', 0)
    createColumnIfNotExist(df, 'PHT_cable_car', 0)
    createColumnIfNotExist(df, 'PHT_car', 0)
    createColumnIfNotExist(df, 'PHT_ferry', 0)
    createColumnIfNotExist(df, 'PHT_rail', 0)
    createColumnIfNotExist(df, 'PHT_subway', 0)
    createColumnIfNotExist(df, 'PHT_tram', 0)
    createColumnIfNotExist(df, 'PHT_walk', 0)
    createColumnIfNotExist(df, 'PHT_car_CAV', 0)
    createColumnIfNotExist(df, 'PHT_car_RH', 0)
    createColumnIfNotExist(df, 'PHT_car_RH_CAV', 0)

    createColumnIfNotExist(df, 'VMT_car', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)

def pltAverageSpeed_internal(_plt_setup3, _output_folder, outputFileName, plotOption ):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    factor = _plt_setup3['expansion_factor']
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.{}.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],outputFileName)
    output_csv = '{}/{}/{}.{}.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],outputFileName)

    createColumnIfDoesNotExist(df)

    if plotOption=='personAverageSpeedOnlyCar':

        df['averageSpeed'] = (df['PMT_car'].values+df['PMT_car_CAV'].values+df['PMT_car_RH'].values + df['PMT_car_RH_CAV'].values)/(df['PHT_car'].values+df['PHT_car_CAV'].values+df['PHT_car_RH'].values + df['PHT_car_RH_CAV'].values)
    elif plotOption=='personAverageSpeedAllModes':
        df['averageSpeed'] = df[['PMT_bike','PMT_bus','PMT_cable_car','PMT_car','PMT_ferry','PMT_rail','PMT_subway','PMT_tram','PMT_walk','PMT_car_CAV','PMT_car_RH','PMT_car_RH_CAV']].sum(axis=1)/df[['PHT_bike','PHT_bus','PHT_cable_car','PHT_car','PHT_ferry','PHT_rail','PHT_subway','PHT_tram','PHT_walk','PHT_car_CAV','PHT_car_RH','PHT_car_RH_CAV']].sum(axis=1)
    elif plotOption=='averageSpeedLDV':
        tot_ldv_vmt = (df['VMT_car'].values+df['VMT_car_CAV'].values+df['VMT_car_RH'].values + df['VMT_car_RH_CAV'].values)
        df['averageSpeed'] = tot_ldv_vmt/df['total_VHT_LightDutyVehicles']

    data = pd.DataFrame(
        {
            'averageSpeed': df[['averageSpeed']].sum(axis=1),
        })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_non_rh_ldv = plt.bar(x=top_labels_xpos, height=data['averageSpeed'], color=colors['grey'],zorder=3)

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)

    #plt.axhline(color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Speed [miles/hour]')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()

def pltLdvRhOccupancy(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    factor = _plt_setup3['expansion_factor']
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.ldv_rh_occupancy.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.ldv_rh_occupancy.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    createColumnIfNotExist(df, 'PMT_car_CAV', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV_shared', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV_shared_2p', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV_shared_3p', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV_shared_4p', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    data = pd.DataFrame(
        {
            'non_rh_ldv': df[['PMT_car', 'PMT_car_CAV']].sum(axis=1),
            'rh_1p': df[['PMT_car_RH', 'PMT_car_RH_CAV']].sum(axis=1)-df[['PMT_car_RH_shared', 'PMT_car_RH_CAV_shared']].sum(axis=1),
            'rh_2p': df[['PMT_car_RH_shared_2p', 'PMT_car_RH_CAV_shared_2p']].sum(axis=1),
            'rh_3p': df[['PMT_car_RH_shared_3p', 'PMT_car_RH_CAV_shared_3p']].sum(axis=1),
            'rh_4p': df[['PMT_car_RH_shared_4p', 'PMT_car_RH_CAV_shared_4p']].sum(axis=1)
        })
    data = data.div(df[['VMT_car', 'VMT_car_CAV', 'VMT_car_RH', 'VMT_car_RH_CAV']].sum(axis=1), axis=0)
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_non_rh_ldv = plt.bar(x=top_labels_xpos, height=data['non_rh_ldv']+data['rh_1p']+data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), color=colors['grey'],zorder=3)


    #     plt_non_rh_ldv = plt.bar(x=top_labels_xpos, height=data['non_rh_ldv'], color=colors['grey'])
    #     plt_rh_1p = plt.bar(x=top_labels_xpos, height=data['rh_1p'], bottom=data['non_rh_ldv'], color=mode_colors['RH'])
    #     plt_rh_shared = plt.bar(x=top_labels_xpos, height=data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), bottom=data[['non_rh_ldv', 'rh_1p']].sum(axis=1), color=mode_colors['RHP'])
    #     plt.bar(x=top_labels_xpos, height=data['rh_2p'], bottom=data[['non_rh_ldv', 'rh_1p']].sum(axis=1), hatch='xxx', fill=False, linewidth=0)
    #     plt.bar(x=top_labels_xpos, height=data['rh_3p'], bottom=data[['non_rh_ldv', 'rh_1p', 'rh_2p']].sum(axis=1), hatch='|||', fill=False, linewidth=0)
    #     plt.bar(x=top_labels_xpos, height=data['rh_4p'], bottom=data[['non_rh_ldv', 'rh_1p', 'rh_2p', 'rh_3p']].sum(axis=1), hatch='....', fill=False, linewidth=0)
    #     shared_2p = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
    #     shared_3p = mpatches.Patch(facecolor='white', label='The white data', hatch='|||')
    #     shared_4p = mpatches.Patch(facecolor='white', label='The white data', hatch='....')

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    #     plt.legend((plt_non_rh_ldv, plt_rh_1p, plt_rh_shared, shared_2p, shared_3p, shared_4p),
    #                ('non-Ridehail LDV', 'Ridehail', 'Ridehail Pool', '2 passengers', '3 passengers', '4+ passengers'),
    #                labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    #plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Distance Based Occupancy')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()
    print('top_labels_xpos',top_labels_xpos)
    print('bottom_labels_xpos',bottom_labels_xpos)


def pltLdvRhOccupancyByVMT(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    factor = _plt_setup3['expansion_factor']
    scale = 1 / 1000000
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.ldv_rh_occupancy_vmt.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.ldv_rh_occupancy_vmt.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_empty', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_empty', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared_2p', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared_3p', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared_4p', 0)

    data = pd.DataFrame(
        {
            'car': (df[['VMT_car', 'VMT_car_CAV']].sum(axis=1)-df[['VMT_car_shared', 'VMT_car_CAV_shared']].sum(axis=1)) * factor * scale,
            'car_shared': df[['VMT_car_shared', 'VMT_car_CAV_shared']].sum(axis=1) * factor * scale,
            'rh': (df[['VMT_car_RH', 'VMT_car_RH_CAV']].sum(axis=1)-df[['VMT_car_RH_shared', 'VMT_car_RH_CAV_shared']].sum(axis=1)) * factor * scale,
            'rh_2p': df[['VMT_car_RH_shared_2p', 'VMT_car_RH_CAV_shared_2p']].sum(axis=1) * factor * scale,
            'rh_3p': df[['VMT_car_RH_shared_3p', 'VMT_car_RH_CAV_shared_3p']].sum(axis=1) * factor * scale,
            'rh_4p': df[['VMT_car_RH_shared_4p', 'VMT_car_RH_CAV_shared_4p']].sum(axis=1) * factor * scale
        })
    height_all = data.sum(axis=1)
    data['car_empty'] = df[['VMT_car_empty', 'VMT_car_CAV_empty']].sum(axis=1) * factor * scale
    data['rh_empty'] = df[['VMT_car_RH_empty', 'VMT_car_RH_CAV_empty']].sum(axis=1) * factor * scale
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], color=mode_colors['Car'],zorder=3)
    plt.bar(x=top_labels_xpos, height=-data['car_empty'], bottom=data['car'], hatch='///', fill=False, linewidth=0,zorder=3)
    plt_car_shared = plt.bar(x=top_labels_xpos, height=data['car_shared'], bottom=data['car'], color=mode_colors['CAV'],zorder=3)
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['car', 'car_shared']].sum(axis=1), color=mode_colors['RH'],zorder=3)
    plt.bar(x=top_labels_xpos, height=-data['rh_empty'], bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), hatch='///', fill=False, linewidth=0,zorder=3)

    plt_rh_shared = plt.bar(x=top_labels_xpos, height=data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), color=mode_colors['RHP'],zorder=3)
    plt.bar(x=top_labels_xpos, height=data['rh_2p'], bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), hatch='xxx', fill=False, linewidth=0,zorder=3)
    plt.bar(x=top_labels_xpos, height=data['rh_3p'], bottom=data[['car', 'car_shared', 'rh', 'rh_2p']].sum(axis=1), hatch='|||', fill=False, linewidth=0,zorder=3)
    plt.bar(x=top_labels_xpos, height=data['rh_4p'], bottom=data[['car', 'car_shared', 'rh', 'rh_2p', 'rh_3p']].sum(axis=1), hatch='....', fill=False, linewidth=0,zorder=3)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
    shared_2p = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
    shared_3p = mpatches.Patch(facecolor='white', label='The white data', hatch='|||')
    shared_4p = mpatches.Patch(facecolor='white', label='The white data', hatch='....')

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    plt.legend((plt_car, plt_car_shared, plt_rh, plt_rh_shared, shared_2p, shared_3p, shared_4p, empty),
               ('Car/CAV', 'CAV Shared', 'Ridehail', 'Ridehail Pool', '2 passengers', '3 passengers', '4+ passengers', 'Deadheading'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvPersonHourTraveled(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    factor = _plt_setup3['expansion_factor']
    scale = 1 / 1000000 / 60
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.ldv_person_hours_traveled.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.ldv_person_hours_traveled.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    createColumnIfNotExist(df, 'personTravelTime_cav', 0)

    data = pd.DataFrame(
        {'car': df['personTravelTime_car'].values * factor * scale,
         'cav': df['personTravelTime_cav'].values * factor * scale,
         'rh': df['personTravelTime_onDemandRide'].values * factor * scale,
         'rhp': df['personTravelTime_onDemandRide_pooled'].values * factor * scale
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], color=mode_colors['Car'],zorder=3)
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data['car'], color=mode_colors['CAV'],zorder=3)
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['car', 'cav']].sum(axis=1), color=mode_colors['RH'],zorder=3)
    plt_rhp = plt.bar(x=top_labels_xpos, height=data['rhp'], bottom=data[['car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'],zorder=3)

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    plt.legend((plt_car, plt_cav, plt_rh, plt_rhp),
               ('Car', 'CAV', 'Ridehail', 'Ridehail Pool'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Person Hours Traveled in LDV (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()




def pltModeSplitInPMT(_plt_setup3, _output_folder):
    pltModeSplitInPMT_internal(_plt_setup3, _output_folder,_plt_setup3['expansion_factor'],'modesplit_pmt',1 / 1000000,'Person Miles Traveled (millions)')


def pltModeSplitInPHT(_plt_setup3, _output_folder):
    pltModeSplitInPHT_internal(_plt_setup3, _output_folder,_plt_setup3['expansion_factor'],'modesplit_pht',1 / 1000000,'Person Hours Traveled (millions)')


def pltModeSplitInPMTPerCapita(_plt_setup3, _output_folder):
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    pltModeSplitInPMT_internal(_plt_setup3, _output_folder,_plt_setup3['percapita_factor']/df['population'].values,'modesplit_pmt_per_capita',1,'Person Miles Traveled')


def pltModeSplitInPHTPerCapita(_plt_setup3, _output_folder):
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    pltModeSplitInPHT_internal(_plt_setup3, _output_folder,_plt_setup3['percapita_factor']/df['population'].values,'modesplit_pht_per_capita',1,'Person Hours Traveled')


def pltModeSplitInPMT_internal(_plt_setup3, _output_folder,factor,fileNameLabel,scale,ylabel):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    #factor = _plt_setup3['expansion_factor']
    #scale = 1 / 1000000
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.{}.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],fileNameLabel)
    output_csv = '{}/{}/{}.{}.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],fileNameLabel)

    createColumnIfNotExist(df, 'PMT_car_CAV', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV', 0)

    data = pd.DataFrame(
        {'transit': (df['PMT_bus'].values+df['PMT_ferry'].values+df['PMT_rail'].values+df['PMT_subway'].values+
                     df['PMT_tram'].values+df['PMT_cable_car'].values) * factor * scale,
         'car': df['PMT_car'].values * factor * scale,
         'cav': df['PMT_car_CAV'].values * factor * scale,
         'rh': (df['PMT_car_RH'].values+df['PMT_car_RH_CAV'].values) * factor * scale,
         'walk': df['PMT_walk'].values * factor * scale,
         'bike': df['PMT_bike'].values * factor * scale
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_transit = plt.bar(x=top_labels_xpos, height=data['transit'], color=mode_colors['Transit'],zorder=3)
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'],zorder=3)
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'],zorder=3)
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'],zorder=3)
    plt_bike = plt.bar(x=top_labels_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['Bike'],zorder=3)
    plt_walk = plt.bar(x=top_labels_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'bike']].sum(axis=1), color=mode_colors['Walk'],zorder=3)

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel(ylabel)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltModeSplitInPHT_internal(_plt_setup3, _output_folder,factor,fileNameLabel,scale,ylabel):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    #factor = _plt_setup3['expansion_factor']
    #scale = 1 / 1000000
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.{}.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],fileNameLabel)
    output_csv = '{}/{}/{}.{}.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],fileNameLabel)

    createColumnIfNotExist(df, 'PHT_car_CAV', 0)
    createColumnIfNotExist(df, 'PHT_car_RH_CAV', 0)
    print(df)
    data = pd.DataFrame(
        {'transit': (df['PHT_bus'].values+df['PHT_ferry'].values+df['PHT_rail'].values+df['PHT_subway'].values+
                     df['PHT_tram'].values+df['PHT_cable_car'].values) * factor * scale,
         'car': df['PHT_car'].values * factor * scale,
         'cav': df['PHT_car_CAV'].values * factor * scale,
         'rh': (df['PHT_car_RH'].values+df['PHT_car_RH_CAV'].values) * factor * scale,
         'walk': df['PHT_walk'].values * factor * scale,
         'bike': df['PHT_bike'].values * factor * scale
         })
    print(data)
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_transit = plt.bar(x=top_labels_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_bike = plt.bar(x=top_labels_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['Bike'])
    plt_walk = plt.bar(x=top_labels_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'bike']].sum(axis=1), color=mode_colors['Walk'])

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel(ylabel)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltModeSplitInVMT_withoutNonMotorizedMode(_plt_setup3, _output_folder):
    pltModeSplitInVMT_internal(_plt_setup3, _output_folder,_plt_setup3['expansion_factor'],'modesplit_vmt_withoutNonMotorizedMode',1 / 1000000,'Vehicle Miles Traveled (millions)',1,False)

def pltModeSplitInVMTPerCapita_withoutNonMotorizedMode(_plt_setup3, _output_folder):
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    pltModeSplitInVMT_internal(_plt_setup3, _output_folder,_plt_setup3['percapita_factor']/df['population'].values,'modesplit_vmt_per_capita_withoutNonMotorizedMode',1,'Vehicle Miles Traveled',1/_plt_setup3['expansion_factor']/df['population'].values,False)

def pltModeSplitInVMT(_plt_setup3, _output_folder):
    pltModeSplitInVMT_internal(_plt_setup3, _output_folder,_plt_setup3['expansion_factor'],'modesplit_vmt',1 / 1000000,'Vehicle Miles Traveled (millions)',1,True)

def pltModeSplitInVMTPerCapita(_plt_setup3, _output_folder):
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    pltModeSplitInVMT_internal(_plt_setup3, _output_folder,_plt_setup3['percapita_factor']/df['population'].values,'modesplit_vmt_per_capita',1,'Vehicle Miles Traveled',1/_plt_setup3['expansion_factor']/df['population'].values,True)

def pltModeSplitInVMT_internal(_plt_setup3, _output_folder,factor,fileNameLabel,scale,ylabel,transitFactor,withNonMotorizedMode):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    #factor = _plt_setup3['expansion_factor']
    #scale = 1 / 1000000
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.{}.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],fileNameLabel)
    output_csv = '{}/{}/{}.{}.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'],fileNameLabel)

    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_empty', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_empty', 0)

    data = pd.DataFrame(
        {'transit': (df['VMT_bus'].values+df['VMT_ferry'].values+df['VMT_rail'].values+df['VMT_subway'].values+
                     df['VMT_tram'].values+df['VMT_cable_car'].values) * scale * transitFactor,
         'car': df['VMT_car'].values * factor * scale,
         'cav': df['VMT_car_CAV'].values * factor * scale,
         'rh': (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values-df['VMT_car_RH_shared'].values-df['VMT_car_RH_CAV_shared'].values) * factor * scale,
         'rhp':(df['VMT_car_RH_shared'].values + df['VMT_car_RH_CAV_shared'].values) * factor * scale,
         'nm': (df['VMT_walk'].values+df['VMT_bike'].values) * factor * scale
         })
    height_all = data.sum(axis=1)
    data['cav_empty'] = df['VMT_car_CAV_empty'].values * factor * scale
    data['cav_shared'] = df['VMT_car_CAV_shared'].values * factor * scale
    data['rh_empty'] = (df['VMT_car_RH_empty'].values + df['VMT_car_RH_CAV_empty'].values) * factor * scale
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_transit = plt.bar(x=top_labels_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_rhp = plt.bar(x=top_labels_xpos, height=data['rhp'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'])

    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')

    plt.bar(x=top_labels_xpos, height=-data['cav_empty'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), hatch='///', fill=False, linewidth=0,zorder=3)
    plt.bar(x=top_labels_xpos, height=-data['rh_empty'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), hatch='///', fill=False, linewidth=0,zorder=3)

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    if withNonMotorizedMode:
        plt_nm = plt.bar(x=top_labels_xpos, height=data['nm'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp']].sum(axis=1), color=mode_colors['NM'])
        plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, plt_nm, empty),
                   ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'NonMotorized', 'Deadheading'),
                   labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    else:
        plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, empty),
                   ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'Deadheading'),
                   labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel(ylabel)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()

def pltLdvTechnologySplitInVMT(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    factor = _plt_setup3['expansion_factor']
    scale = 1 / 1000000
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.ldv_technologysplit_vmt.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.ldv_technologysplit_vmt.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    data = pd.DataFrame(
        {'L1': df['VMT_L1'].values * factor * scale,
         'L3': df['VMT_L3'].values * factor * scale,
         'L5': df['VMT_L5'].values * factor * scale
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_Low = plt.bar(x=top_labels_xpos, height=data['L1'],zorder=3)
    plt_High = plt.bar(x=top_labels_xpos, height=data['L3'], bottom=data['L1'],zorder=3)
    plt_CAV = plt.bar(x=top_labels_xpos, height=data['L5'], bottom=data[['L1', 'L3']].sum(axis=1),zorder=3)
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    plt.legend((plt_Low, plt_High, plt_CAV),
               ('No Automation', 'Partial Automation', 'Full Automation'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHWaitTime(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.rh_wait_time.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.rh_wait_time.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    data = pd.DataFrame(
        {'rh_wait_time': df['averageOnDemandRideWaitTimeInMin'].values.copy()
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt.bar(x=top_labels_xpos, height=data['rh_wait_time'], color=mode_colors['RH'],zorder=3)
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Average Ride Hail Wait (min)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltOverallAverageSpeed(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)

    createColumnIfDoesNotExist(df)

    output_png = '{}/{}/{}.avg_speed.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.avg_speed.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    data = pd.DataFrame(
        {'avg_speed': (df['PMT_bus'].values+df['PMT_ferry'].values+df['PMT_rail'].values+df['PMT_subway'].values+
                       df['PMT_tram'].values+df['PMT_cable_car'].values+df['PMT_car'].values+df['PMT_car_CAV'].values+
                       df['PMT_car_RH'].values+df['PMT_car_RH_CAV'].values+df['PMT_walk'].values+df['PMT_bike']) /
                      (df['PHT_bus'].values+df['PHT_ferry'].values+df['PHT_rail'].values+df['PHT_subway'].values+
                       df['PHT_tram'].values+df['PHT_cable_car'].values+df['PHT_car'].values+df['PHT_car_CAV'].values+
                       df['PHT_car_RH'].values+df['PHT_car_RH_CAV'].values+df['PHT_walk'].values+df['PHT_bike'])
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt.bar(x=top_labels_xpos, height=data['avg_speed'], color=mode_colors['Car'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Average Travel Speed (mi/hr)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()

def pltRHEmptyPooled(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    factor = _plt_setup3['expansion_factor']
    scale = 1 / 1000000
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.rh_empty_shared.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.rh_empty_shared.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_empty', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_empty', 0)

    data = pd.DataFrame(
        {'rh': (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values-df['VMT_car_RH_shared'].values-df['VMT_car_RH_CAV_shared'].values) * factor * scale,
         'rhp': (df['VMT_car_RH_shared'].values+df['VMT_car_RH_CAV_shared'].values) * factor * scale
         })

    #print(df['VMT_car_RH_CAV_shared'])
    #print(data)

    height_all = data.sum(axis=1)
    data['rh_empty'] = (df['VMT_car_RH_empty'].values+df['VMT_car_RH_CAV_empty'].values) * factor * scale
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], color=mode_colors['RH'])
    plt_rhp = plt.bar(x=top_labels_xpos, height=data['rhp'], bottom=data['rh'], color=mode_colors['RHP'])

    plt.bar(x=top_labels_xpos, height=-data['rh_empty'], bottom=data['rh'], hatch='///', fill=False, lw=0,zorder=3)
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
    plt.legend((plt_rh, plt_rhp, empty),
               ('Ridehail', 'Ridehail Pool', 'Deadheading'),
               labelspacing=-2.5, bbox_to_anchor=(1.25, 0.5), frameon=False)

    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Ridehail Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltEnergyPerCapita(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    scale = 2.77778e-13*_plt_setup3['percapita_factor']
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.energy_source_percapita.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.energy_source_percapita.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    data = pd.DataFrame(
        {'gas': (df['totalEnergy_Gasoline'].values / df['population'].values) * scale,
         'diesel': (df['totalEnergy_Diesel'].values / df['population'].values) * scale,
         'electricity': (df['totalEnergy_Electricity'].values / df['population'].values) * scale
         })
    height_all = data.sum(axis=1)
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_Gas = plt.bar(x=top_labels_xpos, height=data['gas'], color=mode_colors['gas'],zorder=3)
    plt_Diesel = plt.bar(x=top_labels_xpos, height=data['diesel'], bottom=data['gas'], color=mode_colors['diesel'],zorder=3)
    plt_Electricity = plt.bar(x=top_labels_xpos, height=data['electricity'], bottom=data[['gas', 'diesel']].sum(axis=1), color=mode_colors['electricity'],zorder=3)
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)
    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Energy per Capita (GWh)')
    plt.legend((plt_Electricity, plt_Diesel, plt_Gas),
               ('Electricity', 'Diesel', 'Gasoline'), bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHAverageChainedTrips(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.rh_chained_trips_requests.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.rh_chained_trips_requests.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    data = pd.DataFrame({'rh_avg': df['chained_trips_requests'].values.copy()})

    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt.bar(x=top_labels_xpos, height=data['rh_avg'], color=mode_colors['RH'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)

    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Average Served Requests Per Chained Trips')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHNumberChainedTrips(_plt_setup3, _output_folder):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.rh_chained_trips_count.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.rh_chained_trips_count.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    data = pd.DataFrame({'rh_nbr': df['chained_trips_count'].values.copy()})

    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt.bar(x=top_labels_xpos, height=data['rh_nbr'], color=mode_colors['RH'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)

    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Number of Chained Trips')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltMEP(_plt_setup3, _output_folder, _mep_data):
    plot_size = _plt_setup3['plot_size']
    top_labels = _plt_setup3['top_labels']
    bottom_labels = _plt_setup3['bottom_labels']
    nb_scenarios = len(_plt_setup3['scenarios_id'])
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup3, _output_folder)
    output_png = '{}/{}/{}.mep.png'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])
    output_csv = '{}/{}/{}.mep.csv'.format(_output_folder,_plt_setup3['plots_folder'], _plt_setup3['name'])

    df['mep'] = _mep_data
    data = pd.DataFrame({'mep': df['mep'].values.copy()})

    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt.bar(x=top_labels_xpos, height=data['mep'], color=colors['grey'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle, ha=horizontal_align)

    ax = plt.gca()
    ax.grid(axis='y',linestyle='dashed', lw=0.5, alpha=0.5,zorder=0)
    max_value = max(height_all)*1.05
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('MEP')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()
