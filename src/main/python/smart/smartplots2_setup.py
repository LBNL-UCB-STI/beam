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

def getDfForPlt(_plt_setup2, _output_folder):
    makeplots_folder = '{}/makeplots'.format(_output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    top_labels = _plt_setup2['top_labels']
    years = _plt_setup2['scenarios_year']
    ids = _plt_setup2['scenarios_id']
    iteration = _plt_setup2['iteration']
    top_labels_xpos = [1]
    bottom_labels_xpos = [1]
    for i in range(1, len(top_labels)):
        top_labels_xpos.append(top_labels_xpos[i-1] + 1 + i % 2)
        if i % 2 == 0:
            bottom_labels_xpos.append((top_labels_xpos[i] + top_labels_xpos[i-1])/2)
    df = pd.DataFrame()
    prefix = ""
    for i in range(len(years)):
        year = years[i]
        if i % 2 == 0:
            prefix += "{}.".format(year)
        id = ids[i]
        metrics_file = "{}/{}.{}.metrics-final.csv".format(_output_folder, year, iteration)
        df_temp = pd.read_csv(metrics_file).fillna(0)
        df = pd.concat([df, df_temp[df_temp['Rank'] == id]])
    return (df.sort_values(by=['Rank']), top_labels_xpos, bottom_labels_xpos, prefix)

def pltModeSplitByTrips2(_plt_setup2, _output_folder):
    makeplots_folder = '{}/makeplots'.format(_output_folder)
    if not os.path.exists(makeplots_folder):
        os.makedirs(makeplots_folder)
    plot_size = _plt_setup2['plot_size']
    top_labels = _plt_setup2['top_labels']
    bottom_labels = _plt_setup2['bottom_labels']
    nb_scenarios = len(_plt_setup2['scenarios_id'])
    angle = 12

    (df, top_labels_xpos, bottom_labels_xpos, prefix) = getDfForPlt(_plt_setup2, _output_folder)
    output_png = '{}/{}modesplit_trips.png'.format(makeplots_folder, prefix)
    output_csv = '{}/{}modesplit_trips.csv'.format(makeplots_folder, prefix)

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