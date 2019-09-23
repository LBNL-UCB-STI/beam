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


def getDfForPlt(_plt_setup2, _output_folder):
    if not os.path.exists('{}/makeplots'.format(_output_folder)):
        os.makedirs('{}/makeplots'.format(_output_folder))
    top_labels = _plt_setup2['top_labels']
    years = _plt_setup2['scenarios_year']
    ids = _plt_setup2['scenarios_id']
    iterations = _plt_setup2['scenarios_itr']
    top_labels_xpos = [1]
    bottom_labels_xpos = [1]
    for i in range(1, len(top_labels)):
        top_labels_xpos.append(top_labels_xpos[i-1] + 1 + i % 2)
        if i % 2 == 0:
            bottom_labels_xpos.append((top_labels_xpos[i] + top_labels_xpos[i-1])/2)
    df = pd.DataFrame()
    for i in range(len(ids)):
        iteration = iterations[i]
        year = years[i]
        id = ids[i]
        metrics_file = "{}/{}.{}.metrics-final.csv".format(_output_folder, year, iteration)
        df_temp = pd.read_csv(metrics_file).fillna(0)
        df = pd.concat([df, df_temp[df_temp['Rank'] == id]])
    return (df.sort_values(by=['Rank']), top_labels_xpos, bottom_labels_xpos)

# plots


def pltModeSplitByTrips(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.modesplit_trips.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.modesplit_trips.csv'.format(_output_folder, _plt_setup2['name'])

    createColumnIfNotExist(df, 'cav_counts', 0)
    data = pd.DataFrame(
        {'transit': (df['drive_transit_counts'].values + df['ride_hail_transit_counts'].values + df['walk_transit_counts'].values),
         'car': df['car_counts'].values,
         'cav': df['cav_counts'].values,
         'rh': df['ride_hail_counts'].values,
         'rhp': df['ride_hail_pooled_counts'].values,
         'walk': df['walk_counts'].values,
         'bike': df['bike_counts'].values
         })
    data = data.div(data.sum(axis=1), axis=0)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_transit = plt.bar(x=top_labels_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_rhp = plt.bar(x=top_labels_xpos, height=data['rhp'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'])
    plt_bike = plt.bar(x=top_labels_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp']].sum(axis=1), color=mode_colors['Bike'])
    plt_walk = plt.bar(x=top_labels_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp', 'bike']].sum(axis=1), color=mode_colors['Walk'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], 1.02, top_labels[ind], ha='center')
    ax.set_ylim((0, 1.0))
    plt.ylabel('Portion of Trips')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def createColumnIfNotExist(df, name, value):
    if name not in df.columns:
        df[name] = value
    else:
        df[name].fillna(0, inplace=True)

def tableSummary(_plt_setup2, _output_folder):
    factor = _plt_setup2['expansion_factor']
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_csv = '{}/makeplots/{}.summary.csv'.format(_output_folder, _plt_setup2['name'])

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
         'Driving Speed [miles/h]': tot_ldv_vmt/df['total_vehicleHoursTravelled_LightDutyVehicles'],
         'Person Hours (10^6)': tot_pht / 60 / 1000000,
         'PEV (%)': 0,
         'Vehicle Energy (GJ)': tot_energy / 1000000000,
         'MEP': 0
         })
    data['Scenario'] = df['Scenario'].values.copy()
    data['Technology'] = df['Technology'].values.copy()
    data['year'] = _plt_setup2['scenarios_year']
    data.to_csv(output_csv)


def pltLdvRhOccupancy(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    factor = _plt_setup2['expansion_factor']
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.ldv_rh_occupancy.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.ldv_rh_occupancy.csv'.format(_output_folder, _plt_setup2['name'])

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
    plt_non_rh_ldv = plt.bar(x=top_labels_xpos, height=data['non_rh_ldv'], color=colors['grey'])
    plt_rh_1p = plt.bar(x=top_labels_xpos, height=data['rh_1p'], bottom=data['non_rh_ldv'], color=mode_colors['RH'])
    plt_rh_shared = plt.bar(x=top_labels_xpos, height=data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), bottom=data[['non_rh_ldv', 'rh_1p']].sum(axis=1), color=mode_colors['RHP'])
    plt.bar(x=top_labels_xpos, height=data['rh_2p'], bottom=data[['non_rh_ldv', 'rh_1p']].sum(axis=1), hatch='xxx', fill=False, linewidth=0)
    plt.bar(x=top_labels_xpos, height=data['rh_3p'], bottom=data[['non_rh_ldv', 'rh_1p', 'rh_2p']].sum(axis=1), hatch='|||', fill=False, linewidth=0)
    plt.bar(x=top_labels_xpos, height=data['rh_4p'], bottom=data[['non_rh_ldv', 'rh_1p', 'rh_2p', 'rh_3p']].sum(axis=1), hatch='....', fill=False, linewidth=0)
    shared_2p = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
    shared_3p = mpatches.Patch(facecolor='white', label='The white data', hatch='|||')
    shared_4p = mpatches.Patch(facecolor='white', label='The white data', hatch='....')

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_non_rh_ldv, plt_rh_1p, plt_rh_shared, shared_2p, shared_3p, shared_4p),
               ('non-Ridehail LDV', 'Ridehail', 'Ridehail Pool', '2 passengers', '3 passengers', '4+ passengers'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Distance Based Occupancy')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvRhOccupancyByVMT(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    factor = _plt_setup2['expansion_factor']
    scale = 1 / 1000000
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.ldv_rh_occupancy_vmt.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.ldv_rh_occupancy_vmt.csv'.format(_output_folder, _plt_setup2['name'])

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
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], color=mode_colors['Car'])
    plt.bar(x=top_labels_xpos, height=-data['car_empty'], bottom=data['car'], hatch='///', fill=False, linewidth=0)
    plt_car_shared = plt.bar(x=top_labels_xpos, height=data['car_shared'], bottom=data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['car', 'car_shared']].sum(axis=1), color=mode_colors['RH'])
    plt.bar(x=top_labels_xpos, height=-data['rh_empty'], bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), hatch='///', fill=False, linewidth=0)

    plt_rh_shared = plt.bar(x=top_labels_xpos, height=data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), color=mode_colors['RHP'])
    plt.bar(x=top_labels_xpos, height=data['rh_2p'], bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), hatch='xxx', fill=False, linewidth=0)
    plt.bar(x=top_labels_xpos, height=data['rh_3p'], bottom=data[['car', 'car_shared', 'rh', 'rh_2p']].sum(axis=1), hatch='|||', fill=False, linewidth=0)
    plt.bar(x=top_labels_xpos, height=data['rh_4p'], bottom=data[['car', 'car_shared', 'rh', 'rh_2p', 'rh_3p']].sum(axis=1), hatch='....', fill=False, linewidth=0)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
    shared_2p = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
    shared_3p = mpatches.Patch(facecolor='white', label='The white data', hatch='|||')
    shared_4p = mpatches.Patch(facecolor='white', label='The white data', hatch='....')

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_car, plt_car_shared, plt_rh, plt_rh_shared, shared_2p, shared_3p, shared_4p, empty),
               ('Car/CAV', 'CAV Shared', 'Ridehail', 'Ridehail Pool', '2 passengers', '3 passengers', '4+ passengers', 'Deadheading'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvPersonHourTraveled(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    factor = _plt_setup2['expansion_factor']
    scale = 1 / 1000000 / 60
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.ldv_person_hours_traveled.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.ldv_person_hours_traveled.csv'.format(_output_folder, _plt_setup2['name'])

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
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_rhp = plt.bar(x=top_labels_xpos, height=data['rhp'], bottom=data[['car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'])

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_car, plt_cav, plt_rh, plt_rhp),
               ('Car', 'CAV', 'Ridehail', 'Ridehail Pool'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Person Hours Traveled in LDV (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


 
    
def pltModeSplitInPMT(_plt_setup2, _output_folder):
    pltModeSplitInPMT_internal(_plt_setup2, _output_folder,_plt_setup2['expansion_factor'],'modesplit_pmt',1 / 1000000,'Person Miles Traveled (millions)')
    
def pltModeSplitInPMTPerCapita(_plt_setup2, _output_folder):
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    pltModeSplitInPMT_internal(_plt_setup2, _output_folder,1/df['population'].values,'modesplit_pmt_per_capita',1,'Person Miles Traveled')

def pltModeSplitInPMT_internal(_plt_setup2, _output_folder,factor,fileNameLabel,scale,ylabel):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    #factor = _plt_setup2['expansion_factor']
    #scale = 1 / 1000000
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.{}.png'.format(_output_folder, _plt_setup2['name'],fileNameLabel)
    output_csv = '{}/makeplots/{}.{}.csv'.format(_output_folder, _plt_setup2['name'],fileNameLabel)

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
    plt_transit = plt.bar(x=top_labels_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=top_labels_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=top_labels_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=top_labels_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_bike = plt.bar(x=top_labels_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['Bike'])
    plt_walk = plt.bar(x=top_labels_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'bike']].sum(axis=1), color=mode_colors['Walk'])

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel(ylabel)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltModeSplitInVMT(_plt_setup2, _output_folder):
    pltModeSplitInVMT_internal(_plt_setup2, _output_folder,_plt_setup2['expansion_factor'],'modesplit_vmt',1 / 1000000,'Vehicle Miles Traveled (millions)',1)
    
def pltModeSplitInVMTPerCapita(_plt_setup2, _output_folder):
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    pltModeSplitInVMT_internal(_plt_setup2, _output_folder,1/df['population'].values,'modesplit_vmt_per_capita',1,'Vehicle Miles Traveled',1/_plt_setup2['expansion_factor']/df['population'].values)
    
def pltModeSplitInVMT_internal(_plt_setup2, _output_folder,factor,fileNameLabel,scale,ylabel,transitFactor):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    #factor = _plt_setup2['expansion_factor']
    #scale = 1 / 1000000
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.{}.png'.format(_output_folder, _plt_setup2['name'],fileNameLabel)
    output_csv = '{}/makeplots/{}.{}.csv'.format(_output_folder, _plt_setup2['name'],fileNameLabel)

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
    plt_nm = plt.bar(x=top_labels_xpos, height=data['nm'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp']].sum(axis=1), color=mode_colors['NM'])
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')

    plt.bar(x=top_labels_xpos, height=-data['cav_empty'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), hatch='///', fill=False, linewidth=0)
    plt.bar(x=top_labels_xpos, height=-data['rh_empty'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), hatch='///', fill=False, linewidth=0)

    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, plt_nm, empty),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'NonMotorized', 'Deadheading'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel(ylabel)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()



def pltLdvTechnologySplitInVMT(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    factor = _plt_setup2['expansion_factor']
    scale = 1 / 1000000
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.ldv_technologysplit_vmt.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.ldv_technologysplit_vmt.csv'.format(_output_folder, _plt_setup2['name'])

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
    plt_Low = plt.bar(x=top_labels_xpos, height=data['L1'])
    plt_High = plt.bar(x=top_labels_xpos, height=data['L3'], bottom=data['L1'])
    plt_CAV = plt.bar(x=top_labels_xpos, height=data['L5'], bottom=data[['L1', 'L3']].sum(axis=1))
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    plt.legend((plt_Low, plt_High, plt_CAV),
               ('No Automation', 'Partial Automation', 'Full Automation'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHWaitTime(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.rh_wait_time.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.rh_wait_time.csv'.format(_output_folder, _plt_setup2['name'])

    data = pd.DataFrame(
        {'rh_wait_time': df['averageOnDemandRideWaitTimeInMin'].values.copy()
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt.bar(x=top_labels_xpos, height=data['rh_wait_time'], color=mode_colors['RH'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Average Ride Hail Wait (min)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHEmptyPooled(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    factor = _plt_setup2['expansion_factor']
    scale = 1 / 1000000
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.rh_empty_shared.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.rh_empty_shared.csv'.format(_output_folder, _plt_setup2['name'])

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

    plt.bar(x=top_labels_xpos, height=-data['rh_empty'], bottom=data['rh'], hatch='///', fill=False, lw=0)
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')

    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value, top_labels[ind], ha='center')
    plt.ylabel('Ridehail Vehicle Miles Traveled (millions)')
    plt.legend((plt_rh, plt_rhp, empty),
               ('Ridehail', 'Ridehail Pool', 'Deadheading'),
               bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltEnergyPerCapita(_plt_setup2, _output_folder):
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    scale = 1 / 1000000000
    angle = 12
    (df, top_labels_xpos, bottom_labels_xpos) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/makeplots/{}.energy_source_percapita.png'.format(_output_folder, _plt_setup2['name'])
    output_csv = '{}/makeplots/{}.energy_source_percapita.csv'.format(_output_folder, _plt_setup2['name'])

    data = pd.DataFrame(
        {'gas': (df['totalEnergy_Gasoline'].values / df['population'].values) * scale,
         'diesel': (df['totalEnergy_Diesel'].values / df['population'].values) * scale,
         'electricity': (df['totalEnergy_Electricity'].values / df['population'].values) * scale
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=plot_size)
    plt_Gas = plt.bar(x=top_labels_xpos, height=data['gas'], color=mode_colors['gas'])
    plt_Diesel = plt.bar(x=top_labels_xpos, height=data['diesel'], bottom=data['gas'], color=mode_colors['diesel'])
    plt_Electricity = plt.bar(x=top_labels_xpos, height=data['electricity'], bottom=data[['gas', 'diesel']].sum(axis=1), color=mode_colors['electricity'])
    plt.xticks(bottom_labels_xpos, bottom_labels, rotation=angle)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(nb_scenarios):
        plt.text(top_labels_xpos[ind], max_value + 0.02*max_value,  top_labels[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Energy per Capita (GJ)')
    plt.legend((plt_Electricity, plt_Diesel, plt_Gas),
               ('Electricity', 'Diesel', 'Gasoline'), bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()
