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


# Plots

def createColumnIfNotExist(df, name, value):
    if name not in df.columns:
        df[name] = value

def tableSummary(_plt_setup, _df, _output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(_output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_csv = '{}/{}.summary.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    factor = _plt_setup['expansion_factor']

    print('ddd')

    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    tot_vmt = (df['VMT_bus'].values+df['VMT_cable_car'].values+df['VMT_ferry'].values+df['VMT_rail'].values +
               df['VMT_subway'].values+df['VMT_tram'].values) +\
              (df['VMT_car'].values+df['VMT_car_CAV'].values+df['VMT_car_RH'].values +
               df['VMT_car_RH_CAV'].values +
               df['VMT_walk'].values+df['VMT_bike'].values) * factor
    createColumnIfNotExist(df, 'personTravelTime_cav', 0)
    tot_pht = (df['personTravelTime_bike'].values+df['personTravelTime_car'].values+df['personTravelTime_cav'].values +
               df['personTravelTime_drive_transit'].values+df['personTravelTime_mixed_mode'].values +
               df['personTravelTime_onDemandRide'].values+df['personTravelTime_onDemandRide_pooled'].values +
               df['personTravelTime_onDemandRide_transit'].values+df['personTravelTime_walk'].values +
               df['personTravelTime_walk_transit'].values) * factor

    tot_energy = (df['totalEnergy_Biodiesel'].values+df['totalEnergy_Diesel'].values +
                  df['totalEnergy_Electricity'].values+df['totalEnergy_Gasoline'].values) * factor

    data = pd.DataFrame(
        {'VMT Total (10^6)': tot_vmt / 1000000,
         'VMT per Capita': (tot_vmt/df['population']),
         'Driving Speed': 0,
         'Person Hours (10^6)': tot_pht / 60 / 1000000,
         'PEV (%)': 0,
         'Vehicle Energy (GJ)': tot_energy / 1000000000,
         'MEP': 0
         })
    data['Scenario'] = df['Scenario'].values.copy()
    data['Technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)


def pltModeSplitByTrips(_plt_setup, _df, _output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(_output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.modesplit_trips.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.modesplit_trips.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']

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

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_transit = plt.bar(x=t_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_rhp = plt.bar(x=t_xpos, height=data['rhp'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'])
    plt_bike = plt.bar(x=t_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp']].sum(axis=1), color=mode_colors['Bike'])
    plt_walk = plt.bar(x=t_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp', 'bike']].sum(axis=1), color=mode_colors['Walk'])

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    for ind in range(dimension):
        plt.text(t_xpos[ind], 1.02, t_names[ind], ha='center')
    ax.set_ylim((0, 1.0))
    plt.ylabel('Portion of Trips')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltEnergyPerCapita(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.energy_source_percapita.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.energy_source_percapita.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']

    data = pd.DataFrame(
        {'gas': (df['totalEnergy_Gasoline'].values / df['population'].values)  / 1000000000,
         'diesel': (df['totalEnergy_Diesel'].values / df['population'].values) / 1000000000,
         'electricity': (df['totalEnergy_Electricity'].values / df['population'].values) / 1000000000
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_Gas = plt.bar(x=t_xpos, height=data['gas'], color=mode_colors['gas'])
    plt_Diesel = plt.bar(x=t_xpos, height=data['diesel'], bottom=data['gas'], color=mode_colors['diesel'])
    plt_Electricity = plt.bar(x=t_xpos, height=data['electricity'], bottom=data[['gas', 'diesel']].sum(axis=1), color=mode_colors['electricity'])
    plt.xticks(s_xpos, s_names, rotation=angle)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value,  t_names[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Energy per Capita (GJ)')
    plt.legend((plt_Electricity, plt_Diesel, plt_Gas),
               ('Electricity', 'Diesel', 'Gasoline'), bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvRhOccupancy(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.ldv_rh_occupancy.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.ldv_rh_occupancy.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']

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

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_non_rh_ldv = plt.bar(x=t_xpos, height=data['non_rh_ldv'], color=colors['grey'])
    plt_rh_1p = plt.bar(x=t_xpos, height=data['rh_1p'], bottom=data['non_rh_ldv'], color=mode_colors['RH'])
    plt_rh_shared = plt.bar(x=t_xpos, height=data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), bottom=data[['non_rh_ldv', 'rh_1p']].sum(axis=1), color=mode_colors['RHP'])
    plt.bar(x=t_xpos, height=data['rh_2p'], bottom=data[['non_rh_ldv', 'rh_1p']].sum(axis=1), hatch='xxx', fill=False, linewidth=0)
    plt.bar(x=t_xpos, height=data['rh_3p'], bottom=data[['non_rh_ldv', 'rh_1p', 'rh_2p']].sum(axis=1), hatch='|||', fill=False, linewidth=0)
    plt.bar(x=t_xpos, height=data['rh_4p'], bottom=data[['non_rh_ldv', 'rh_1p', 'rh_2p', 'rh_3p']].sum(axis=1), hatch='....', fill=False, linewidth=0)
    shared_2p = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
    shared_3p = mpatches.Patch(facecolor='white', label='The white data', hatch='|||')
    shared_4p = mpatches.Patch(facecolor='white', label='The white data', hatch='....')

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_non_rh_ldv, plt_rh_1p, plt_rh_shared, shared_2p, shared_3p, shared_4p),
               ('non-Ridehail LDV', 'Ridehail', 'Ridehail Pool', '2 passengers', '3 passengers', '4+ passengers'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value,  t_names[ind], ha='center')
    plt.ylabel('Distance Based Occupancy')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvRhOccupancyByVMT(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.ldv_rh_occupancy_vmt.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.ldv_rh_occupancy_vmt.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']

    factor = _plt_setup['expansion_factor'] / 1000000

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
            'car': (df[['VMT_car', 'VMT_car_CAV']].sum(axis=1)-df[['VMT_car_shared', 'VMT_car_CAV_shared']].sum(axis=1)) * factor,
            'car_shared': df[['VMT_car_shared', 'VMT_car_CAV_shared']].sum(axis=1) * factor,
            'rh': (df[['VMT_car_RH', 'VMT_car_RH_CAV']].sum(axis=1)-df[['VMT_car_RH_shared', 'VMT_car_RH_CAV_shared']].sum(axis=1)) * factor,
            'rh_2p': df[['VMT_car_RH_shared_2p', 'VMT_car_RH_CAV_shared_2p']].sum(axis=1) * factor,
            'rh_3p': df[['VMT_car_RH_shared_3p', 'VMT_car_RH_CAV_shared_3p']].sum(axis=1) * factor,
            'rh_4p': df[['VMT_car_RH_shared_4p', 'VMT_car_RH_CAV_shared_4p']].sum(axis=1) * factor
        })
    height_all = data.sum(axis=1)
    data['car_empty'] = df[['VMT_car_empty', 'VMT_car_CAV_empty']].sum(axis=1) * factor
    data['rh_empty'] = df[['VMT_car_RH_empty', 'VMT_car_RH_CAV_empty']].sum(axis=1) * factor
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], color=mode_colors['Car'])
    plt.bar(x=t_xpos, height=-data['car_empty'], bottom=data['car'], hatch='///', fill=False, linewidth=0)
    plt_car_shared = plt.bar(x=t_xpos, height=data['car_shared'], bottom=data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data[['car', 'car_shared']].sum(axis=1), color=mode_colors['RH'])
    plt.bar(x=t_xpos, height=-data['rh_empty'], bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), hatch='///', fill=False, linewidth=0)

    plt_rh_shared = plt.bar(x=t_xpos, height=data[['rh_2p', 'rh_3p', 'rh_4p']].sum(axis=1), bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), color=mode_colors['RHP'])
    plt.bar(x=t_xpos, height=data['rh_2p'], bottom=data[['car', 'car_shared', 'rh']].sum(axis=1), hatch='xxx', fill=False, linewidth=0)
    plt.bar(x=t_xpos, height=data['rh_3p'], bottom=data[['car', 'car_shared', 'rh', 'rh_2p']].sum(axis=1), hatch='|||', fill=False, linewidth=0)
    plt.bar(x=t_xpos, height=data['rh_4p'], bottom=data[['car', 'car_shared', 'rh', 'rh_2p', 'rh_3p']].sum(axis=1), hatch='....', fill=False, linewidth=0)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
    shared_2p = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
    shared_3p = mpatches.Patch(facecolor='white', label='The white data', hatch='|||')
    shared_4p = mpatches.Patch(facecolor='white', label='The white data', hatch='....')

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_car, plt_car_shared, plt_rh, plt_rh_shared, shared_2p, shared_3p, shared_4p, empty),
               ('Car/CAV', 'CAV Shared', 'Ridehail', 'Ridehail Pool', '2 passengers', '3 passengers', '4+ passengers', 'Deadheading'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5, alpha=0.2)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value,  t_names[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvPersonHourTraveled(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.ldv_person_hours_traveled.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.ldv_person_hours_traveled.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']

    factor = _plt_setup['expansion_factor'] / 1000000 / 60

    createColumnIfNotExist(df, 'personTravelTime_cav', 0)
    data = pd.DataFrame(
        {'car': df['personTravelTime_car'].values * factor,
         'cav': df['personTravelTime_cav'].values * factor,
         'rh': df['personTravelTime_onDemandRide'].values * factor,
         'rhp': df['personTravelTime_onDemandRide_pooled'].values * factor
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data[['car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_rhp = plt.bar(x=t_xpos, height=data['rhp'], bottom=data[['car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'])

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_car, plt_cav, plt_rh, plt_rhp),
               ('Car', 'CAV', 'Ridehail', 'Ridehail Pool'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value, t_names[ind], ha='center')
    plt.ylabel('Person Hours Traveled in LDV (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltModeSplitInPMT(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.modesplit_pmt.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.modesplit_pmt.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']
    figure_size = _plt_setup['fig_size']

    factor = _plt_setup['expansion_factor'] / 1000000
    createColumnIfNotExist(df, 'PMT_car_CAV', 0)
    createColumnIfNotExist(df, 'PMT_car_RH_CAV', 0)
    data = pd.DataFrame(
        {'transit': (df['PMT_bus'].values+df['PMT_ferry'].values+df['PMT_rail'].values+df['PMT_subway'].values+
                     df['PMT_tram'].values+df['PMT_cable_car'].values) * factor,
         'car': df['PMT_car'].values * factor,
         'cav': df['PMT_car_CAV'].values * factor,
         'rh': (df['PMT_car_RH'].values+df['PMT_car_RH_CAV'].values) * factor,
         'walk': df['PMT_walk'].values * factor,
         'bike': df['PMT_bike'].values * factor
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_transit = plt.bar(x=t_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_bike = plt.bar(x=t_xpos, height=data['bike'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['Bike'])
    plt_walk = plt.bar(x=t_xpos, height=data['walk'], bottom=data[['transit', 'car', 'cav', 'rh', 'bike']].sum(axis=1), color=mode_colors['Walk'])

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_bike, plt_walk),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Bike', 'Walk'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value, t_names[ind], ha='center')
    plt.ylabel('Person Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltModeSplitInVMT(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.modesplit_vmt.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.modesplit_vmt.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']
    figure_size = _plt_setup['fig_size']

    factor = _plt_setup['expansion_factor'] / 1000000

    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_empty', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_empty', 0)
    data = pd.DataFrame(
        {'transit': (df['VMT_bus'].values+df['VMT_ferry'].values+df['VMT_rail'].values+df['VMT_subway'].values+
                     df['VMT_tram'].values+df['VMT_cable_car'].values) / 1000000,
         'car': df['VMT_car'].values * factor,
         'cav': df['VMT_car_CAV'].values * factor,
         'rh': (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values-df['VMT_car_RH_shared'].values-df['VMT_car_RH_CAV_shared'].values) * factor,
         'rhp':(df['VMT_car_RH_shared'].values + df['VMT_car_RH_CAV_shared'].values) * factor,
         'nm': (df['VMT_walk'].values+df['VMT_bike'].values) * factor
         })
    height_all = data.sum(axis=1)
    data['cav_empty'] = df['VMT_car_CAV_empty'].values * factor
    data['cav_shared'] = df['VMT_car_CAV_shared'].values * factor
    data['rh_empty'] = (df['VMT_car_RH_empty'].values + df['VMT_car_RH_CAV_empty'].values) * factor
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_transit = plt.bar(x=t_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data[['transit', 'car']].sum(axis=1), color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), color=mode_colors['RH'])
    plt_rhp = plt.bar(x=t_xpos, height=data['rhp'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), color=mode_colors['RHP'])
    plt_nm = plt.bar(x=t_xpos, height=data['nm'], bottom=data[['transit', 'car', 'cav', 'rh', 'rhp']].sum(axis=1), color=mode_colors['NM'])
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')

    plt.bar(x=t_xpos, height=-data['cav_empty'], bottom=data[['transit', 'car', 'cav']].sum(axis=1), hatch='///', fill=False, linewidth=0)
    plt.bar(x=t_xpos, height=-data['rh_empty'], bottom=data[['transit', 'car', 'cav', 'rh']].sum(axis=1), hatch='///', fill=False, linewidth=0)

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_transit, plt_car, plt_cav, plt_rh, plt_rhp, plt_nm, empty),
               ('Transit', 'Car', 'CAV', 'Ridehail', 'Ridehail Pool', 'NonMotorized', 'Deadheading'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value, t_names[ind], ha='center')
    plt.ylabel('Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltLdvTechnologySplitInVMT(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.ldv_technologysplit_vmt.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.ldv_technologysplit_vmt.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']
    figure_size = _plt_setup['fig_size']

    factor = _plt_setup['expansion_factor'] / 1000000

    data = pd.DataFrame(
        {'L1': df['VMT_L1'].values * factor,
         'L3': df['VMT_L3'].values * factor,
         'L5': df['VMT_L5'].values * factor
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_Low = plt.bar(x=t_xpos, height=data['L1'])
    plt_High = plt.bar(x=t_xpos, height=data['L3'], bottom=data['L1'])
    plt_CAV = plt.bar(x=t_xpos, height=data['L5'], bottom=data[['L1', 'L3']].sum(axis=1))
    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_Low, plt_High, plt_CAV),
               ('No Automation', 'Partial Automation', 'Full Automation'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value, t_names[ind], ha='center')
    plt.ylabel('Vehicle Miles Traveled (millions)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHWaitTime(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.rh_wait_time.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.rh_wait_time.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']
    figure_size = _plt_setup['fig_size']

    data = pd.DataFrame(
        {'rh_wait_time': df['averageOnDemandRideWaitTimeInMin'].values.copy()
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt.bar(x=t_xpos, height=data['rh_wait_time'], color=mode_colors['RH'])
    plt.xticks(s_xpos, s_names, rotation=angle)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value, t_names[ind], ha='center')
    plt.ylabel('Average Ride Hail Wait (min)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHEmptyPooled(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.rh_empty_shared.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.rh_empty_shared.csv'.format(makeplots_folder, _output_plot_prefix)
    df = _df[~_df['Rank'].isin(_plt_setup['rank_to_filterout'])]
    t_xpos = _plt_setup['technologies_xpos']
    t_names = _plt_setup['technologies']
    s_xpos = _plt_setup['scenarios_xpos']
    s_names = _plt_setup['scenarios']
    angle = _plt_setup['rotation']
    dimension = _plt_setup['dimension']
    figure_size = _plt_setup['fig_size']

    factor = _plt_setup['expansion_factor'] / 1000000
    createColumnIfNotExist(df, 'VMT_car_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_empty', 0)
    createColumnIfNotExist(df, 'VMT_car_CAV_shared', 0)
    createColumnIfNotExist(df, 'VMT_car_RH_CAV_empty', 0)
    data = pd.DataFrame(
        {'rh': (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values-df['VMT_car_RH_shared'].values-df['VMT_car_RH_CAV_shared'].values) * factor,
         'rhp': (df['VMT_car_RH_shared'].values+df['VMT_car_RH_CAV_shared'].values) * factor
         })
    height_all = data.sum(axis=1)
    data['rh_empty'] = (df['VMT_car_RH_empty'].values+df['VMT_car_RH_CAV_empty'].values) * factor
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], color=mode_colors['RH'])
    plt_rhp = plt.bar(x=t_xpos, height=data['rhp'], bottom=data['rh'], color=mode_colors['RHP'])

    plt.bar(x=t_xpos, height=-data['rh_empty'], bottom=data['rh'], hatch='///', fill=False, lw=0)
    plt.xticks(s_xpos, s_names, rotation=angle)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')

    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02*max_value, t_names[ind], ha='center')
    plt.ylabel('Ridehail Vehicle Miles Traveled (millions)')
    plt.legend((plt_rh, plt_rhp, empty),
               ('Ridehail', 'Ridehail Pool', 'Deadheading'),
               bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()