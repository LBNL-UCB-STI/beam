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
               'RHP': colors['purple'],
               'CAV': colors['light.yellow'],
               'Bike': colors['light.orange'],
               'NM': colors['light.orange'],
               'electricity': colors['blue'],
               'gas': colors['purple'],
               'diesel': colors['yellow']}


plt_setup_base_smart = {
    'expansion_factor': 8000000/630000,
    'rotation': 13,
    'fig_size': (7.5, 4.5),
    'scenarios': ['Base', 'Base-Short', 'Base-Long', 'Sharing is Caring', 'Technology Takeover', "All About Me"],
    'scenarios_xpos': [1, 3.5, 6.5, 9.5, 12.5, 15.5],
    'technologies': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
    'technologies_xpos': [1, 3, 4, 6, 7, 9, 10, 12, 13, 15, 16],
    'dimension': 11,
    'rank_to_filterout': []
}


plt_setup_smart = {
    'expansion_factor': 8000000/630000,
    'rotation': 11,
    'fig_size': (5, 4.5),
    'scenarios': ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"],
    'scenarios_xpos': [1, 3.5, 6.5, 9.5],
    'technologies': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
    'technologies_xpos': [1, 3, 4, 6, 7, 9, 10],
    'dimension': 7,
    'rank_to_filterout': [2, 3, 4, 5]
}

# Plots


def sum(axis):
    pass


def pltModeSplitInTrips(_plt_setup, _df, _output_folder, _output_plot_prefix):
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

    factor = _plt_setup['expansion_factor'] / 1000000

    data = pd.DataFrame(
        {'transit': (df['drive_transit_counts'].values + df['ride_hail_transit_counts'].values + df['walk_transit_counts'].values) * factor,
         'car': df['car_counts'].values * factor,
         'cav': df['cav_counts'].values * factor,
         'rh': (df['ride_hail_counts'].values + df['ride_hail_pooled_counts'].values) * factor,
         'rhp': df['ride_hail_pooled_counts'].values * factor,
         'nm': (df['walk_counts'].values + df['bike_counts'].values) * factor
        })
    height_all = data.sum(axis=1)
    data['transit'] = data['transit']/height_all
    data['car'] = data['car']/height_all
    data['cav'] = data['cav']/height_all
    data['rh'] = data['rh']/height_all
    data['rhp'] = data['rhp']/height_all
    data['nm'] = data['nm']/height_all
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_transit = plt.bar(x=t_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data['transit']+data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data['transit']+data['car']+data['cav'], color=mode_colors['RH'])
    plt.bar(x=t_xpos, height=data['rhp'], bottom=data['transit']+data['car']+data['cav'], hatch='xx', fill=False)
    plt_nm = plt.bar(x=t_xpos, height=data['nm'], bottom=data['transit']+data['car']+data['cav']+data['rh'], color=mode_colors['NM'])
    pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xx')

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_car, plt_cav, plt_transit, plt_rh, pooled, plt_nm),
               ('Car', 'CAV', 'Transit', 'Ridehail', 'Ridehail Pool', 'NonMotorized'),
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

    factor = _plt_setup['expansion_factor'] / 1000000000

    data = pd.DataFrame(
        {'gas': (df['totalEnergy_Gasoline'].values / df['population'].values) * factor,
         'diesel': (df['totalEnergy_Diesel'].values / df['population'].values) * factor,
         'electricity': (df['totalEnergy_Electricity'].values / df['population'].values) * factor
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_Gas = plt.bar(x=t_xpos, height=data['gas'], color=mode_colors['gas'])
    plt_Diesel = plt.bar(x=t_xpos, height=data['diesel'], bottom=data['gas'], color=mode_colors['diesel'])
    plt_Electricity = plt.bar(x=t_xpos, height=data['electricity'], bottom=data['gas']+data['diesel'], color=mode_colors['electricity'])
    plt.xticks(s_xpos, s_names, rotation=angle)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02,  t_names[ind], ha='center')
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

    totalVMT = df['VMT_car'].values + df['VMT_car_CAV'].values + df['VMT_car_RH'].values + df['VMT_car_RH_CAV'].values
    data = pd.DataFrame(
        {'rh': (df['PMT_car_RH'].values + df['PMT_car_RH_CAV'].values)/totalVMT,
         'all_ldv': (df['PMT_car'].values + df['PMT_car_CAV'].values + df['PMT_car_RH'].values + df['PMT_car_RH_CAV'].values)/totalVMT,
         })
    height_all = data['all_ldv']
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=_plt_setup['fig_size'])
    plt_all = plt.bar(x=t_xpos, height=data['all_ldv'], color=colors['grey'])
    plt.bar(x=t_xpos, height=-data['rh'], bottom=data['all_ldv'], color=colors['grey'], hatch='xx', fill=False)
    plt_rh = mpatches.Patch(facecolor='white', label='The white data', hatch='xx')
    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_all, plt_rh),
               ('LDV Occupancy', 'RHV Occupancy'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.axhline(y=1.0, color='black', linestyle='dashed', lw=0.5)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.02,  t_names[ind], ha='center')
    plt.ylabel('Distance Based Occupancy')
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
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data['car']+data['cav'], color=mode_colors['RH'])
    plt_rhp = plt.bar(x=t_xpos, height=data['rhp'], bottom=data['car']+data['cav']+data['rh'], color=mode_colors['RHP'])

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_car, plt_cav, plt_rh, plt_rhp),
               ('Car', 'CAV', 'Ridehail', 'Ridehail (Pooled)'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.05, t_names[ind], ha='center')
    plt.ylabel('Person Hours Traveled (millions)')
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

    data = pd.DataFrame(
        {'transit': (df['PMT_bus'].values+df['PMT_ferry'].values+df['PMT_rail'].values+df['PMT_subway'].values+df['PMT_tram'].values) * factor,
         'car': df['PMT_car'].values * factor,
         'cav': df['PMT_car_CAV'].values * factor,
         'rh': (df['PMT_car_RH'].values+df['PMT_car_RH_CAV'].values) * factor,
         'nm': (df['PMT_walk'].values+df['PMT_bike'].values) * factor
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_transit = plt.bar(x=t_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data['transit']+data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data['transit']+data['car']+data['cav'], color=mode_colors['RH'])
    plt_nm = plt.bar(x=t_xpos, height=data['nm'], bottom=data['transit']+data['car']+data['cav']+data['rh'], color=mode_colors['NM'])

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_car, plt_cav, plt_rh, plt_transit, plt_nm),
               ('Car', 'CAV', 'Ridehail', 'Transit', 'NonMotorized'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 2, t_names[ind], ha='center')
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

    data = pd.DataFrame(
        {'transit': (df['VMT_bus'].values+df['VMT_ferry'].values+df['VMT_rail'].values+df['VMT_subway'].values+df['VMT_tram'].values) * factor,
         'car': df['VMT_car'].values * factor,
         'cav': df['VMT_car_CAV'].values * factor,
         'rh': (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values) * factor,
         'nm': (df['VMT_walk'].values+df['VMT_bike'].values) * factor
         })
    height_all = data.sum(axis=1)
    data['cav_empty'] = df['VMT_car_CAV_empty'].values * factor
    data['cav_shared'] = df['VMT_car_CAV_shared'].values * factor
    data['rhp'] = (df['VMT_car_RH_pooled'].values + df['VMT_car_RH_CAV_pooled'].values) * factor
    data['rh_empty'] = (df['VMT_car_RH_empty'].values + df['VMT_car_RH_CAV_empty'].values) * factor
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_transit = plt.bar(x=t_xpos, height=data['transit'], color=mode_colors['Transit'])
    plt_car = plt.bar(x=t_xpos, height=data['car'], bottom=data['transit'], color=mode_colors['Car'])
    plt_cav = plt.bar(x=t_xpos, height=data['cav'], bottom=data['transit']+data['car'], color=mode_colors['CAV'])
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], bottom=data['transit']+data['car']+data['cav'], color=mode_colors['RH'])
    plt_nm = plt.bar(x=t_xpos, height=data['nm'], bottom=data['transit']+data['car']+data['cav']+data['rh'], color=mode_colors['NM'])
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///', linewidth=0.1)
    shared = mpatches.Patch(facecolor='white', label='The white data', hatch='xx', linewidth=0.1)

    plt.bar(x=t_xpos, height=-data['cav_empty'], bottom=data['transit']+data['car']+data['cav'], hatch='///', fill=False)
    plt.bar(x=t_xpos, height=data['cav_shared'], bottom=data['transit']+data['car'], hatch="xx", fill=False)
    plt.bar(x=t_xpos, height=-data['rh_empty'], bottom=data['transit']+data['car']+data['cav']+data['rh'], hatch='///', fill=False)
    plt.bar(x=t_xpos, height=data['rhp'], bottom=data['transit']+data['car']+data['cav'], hatch="xx", fill=False)

    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_car, plt_cav, plt_rh, plt_transit, plt_nm, empty, shared),
               ('Car', 'CAV', 'Ridehail', 'Transit', 'NonMotorized'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 2, t_names[ind], ha='center')
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
        {'l1': df['VMT_L1'].values * factor,
         'l3': df['VMT_L3'].values * factor,
         'l5': df['VMT_L5'].values * factor
         })
    height_all = data.sum(axis=1)
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_Low = plt.bar(x=t_xpos, height=data['l1'])
    plt_High = plt.bar(x=t_xpos, height=data['l3'], bottom=data['l1'])
    plt_CAV = plt.bar(x=t_xpos, height=data['l5'], bottom=data['l1']+data['l3'])
    plt.xticks(s_xpos, s_names, rotation=angle)
    plt.legend((plt_Low, plt_High, plt_CAV),
               ('No Automation', 'Partial Automation', 'CAV'),
               labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 2, t_names[ind], ha='center')
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
        plt.text(t_xpos[ind], max_value + 0.1, t_names[ind], ha='center')
    plt.ylabel('Average Ride Hail Wait (min)')
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()


def pltRHEmptyPooled(_plt_setup, _df, output_folder, _output_plot_prefix):
    makeplots_folder = '{}/makeplots'.format(output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    output_png = '{}/{}.rh_empty_pooled.png'.format(makeplots_folder, _output_plot_prefix)
    output_csv = '{}/{}.rh_empty_pooled.csv'.format(makeplots_folder, _output_plot_prefix)
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
        {'rh': (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values) * factor
         })
    height_all = data.sum(axis=1)
    data['rhp'] = (df['VMT_car_RH_pooled'].values+df['VMT_car_RH_CAV_pooled'].values) * factor
    data['rh_empty'] = (df['VMT_car_RH_empty'].values+df['VMT_car_RH_CAV_empty'].values) * factor
    data['scenario'] = df['Scenario'].values.copy()
    data['technology'] = df['Technology'].values.copy()
    data.to_csv(output_csv)

    plt.figure(figsize=figure_size)
    plt_rh = plt.bar(x=t_xpos, height=data['rh'], color=mode_colors['RH'])
    plt.bar(x=t_xpos, height=-data['rh_empty'], bottom=data['rh'], hatch='///', fill=False)
    plt.bar(x=t_xpos, height=data['rhp'], hatch='xx', fill=False)
    plt.xticks(s_xpos, s_names, rotation=angle)
    empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
    pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xx')

    ax = plt.gca()
    ax.grid(False)
    max_value = max(height_all)
    ax.set_ylim((0, max_value))
    for ind in range(dimension):
        plt.text(t_xpos[ind], max_value + 0.5, t_names[ind], ha='center')
    plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
    plt.legend((plt_rh, empty, pooled),
               ('Total Ridehail VMT', 'Empty VMT', 'Shared VMT'),
               bbox_to_anchor=(1.05, 0.5), frameon=False)
    plt.savefig(output_png, transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
    plt.clf()
    plt.close()

