import ConfigParser
from os import path
import time
import sys

import geopandas as gpd
import matplotlib.ticker
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from utils import spatial_tools

__author__ = "Andrew A Campbell"

'''
Calculates and plots a network performance and demand metrics.
'''

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)

    # Complete Paths
    taz_file = conf.get('Paths', 'taz_file')
    county_file = conf.get('Paths', 'county_file')

    # Root Dirs
    matsim_out_root = conf.get('Paths', 'matsim_out_root')
    validation_out_root = conf.get('Paths', 'validation_out_root')

    # File Names
    comm_val_total_name = conf.get('Paths', 'comm_val_total_name')
    comm_val_fwy_name = conf.get('Paths', 'comm_val_fwy_name')

    VIZ_1_out_name = conf.get('Paths', 'VIZ_1_out_name')
    VIZ_2_time_out_name = conf.get('Paths', 'VIZ_2_time_out_name')
    VIZ_3_dist_out_name = conf.get('Paths', 'VIZ_3_dist_out_name')
    VIZ_4_tab_out_name = conf.get('Paths', 'VIZ_4_tab_out_name')
    VIZ_4_stacked_out_name = conf.get('Paths', 'VIZ_4_stacked_out_name')
    VIZ_4_bars_out_name = conf.get('Paths', 'VIZ_4_bars_out_name')

    # Build complete paths
    validation_out_root = conf.get('Paths', 'validation_out_root')
    out_prefix = conf.get('Paths', 'out_prefix')

    commute_validation_total_file = path.join(matsim_out_root, out_prefix + comm_val_total_name)
    commute_validation_fwy_file = path.join(matsim_out_root, out_prefix + comm_val_fwy_name)

    VIZ_1_out_file = path.join(validation_out_root, out_prefix + VIZ_1_out_name)
    VIZ_2_time_out_file = path.join(validation_out_root, out_prefix + VIZ_2_time_out_name)
    VIZ_3_dist_out_file = path.join(validation_out_root, out_prefix + VIZ_3_dist_out_name)
    VIZ_4_tab_out_file = path.join(validation_out_root, out_prefix + VIZ_4_tab_out_name)
    VIZ_4_stacked_out_file = path.join(validation_out_root, out_prefix + VIZ_4_stacked_out_name)
    VIZ_4_bars_out_file = path.join(validation_out_root, out_prefix + VIZ_4_bars_out_name)

    #Params
    pop_total_commuters = np.float(conf.get('Params', 'pop_total_commuters'))  # for scaling agent commuters up to total
    taz_title = conf.get('Params', 'taz_title')
    comm_title = conf.get('Params', 'comm_title')
    min_time = np.float(conf.get('Params', 'min_time'))
    max_time = np.float(conf.get('Params', 'max_time'))
    barx_min = np.float(conf.get('Params', 'barx_min'))
    barx_max = np.float(conf.get('Params', 'barx_max'))


    ####################################################################################################################
    # VIZ_1 - total commute times by TAZ
    ####################################################################################################################

    # Load the TAZ shapefile and validation files
    taz_gdf = gpd.read_file(taz_file)
    crs_orig = {'init' :'epsg:26910'}
    val_total_gdf = spatial_tools.text_to_points_gdf(commute_validation_total_file, 'HomeX', 'HomeY', sep='\t', crs=crs_orig)
    crs_new = {'init' :'epsg:4326'}
    val_total_gdf.to_crs(crs_new, inplace=True)  # project to WGS84

    ##
    # Spatial join map points to TAZs
    ##

    t0 = time.time()
    val_gdf_1 = gpd.sjoin(val_total_gdf, taz_gdf, how='left', op='within')
    print 'Method 1 Time: %f' % (time.time() - t0)

    ##
    # Aggregate values to TAZ level
    ##
    g_1 = val_gdf_1.groupby('taz_key')
    means_1 = g_1.mean()
    means_1['taz_key'] = means_1.index.astype(int)
    # join with the geometries
    merged_1 = taz_gdf.merge(means_1, how='outer', on='taz_key')


    ##
    # Plots
    ##

    # Total HW commute time
    alpha = 1
    linewidth = .1
    clrmap = 'hot'
    merged_1['TotalTimeH2W_Minutes'] = merged_1['TotalTimeH2W'] / 60.0
    # If min and max time set, use them, else use the observed min and max from the data
    if min_time:
        vmin = min_time
        vmax = max_time
    else:
        vmin = np.min(merged_1['TotalTimeH2W_Minutes'])
        vmax = np.max(merged_1['TotalTimeH2W_Minutes'])
    ax = merged_1.plot('TotalTimeH2W_Minutes', colormap=clrmap, vmin=vmin, vmax=vmax, figsize=(15, 12.5),
                       linewidth=linewidth, alpha=alpha)
    ax.set_title(taz_title, fontsize=20)
    ax.get_xaxis().set_ticks([])
    ax.get_yaxis().set_ticks([])

    fig = ax.get_figure()
    cax = fig.add_axes([0.91, 0.11, 0.03, 0.775])
    sm = plt.cm.ScalarMappable(cmap=clrmap, norm=plt.Normalize(vmin=vmin, vmax=vmax))
    # fake up the array of the scalar mappable. Urgh...
    sm._A = []
    cb = fig.colorbar(sm, cax=cax, alpha=alpha)
    cb.set_label('minutes', fontsize=20)
    cb.ax.tick_params(labelsize=15)
    # using ColorBase
    # cb1 = mpl.colorbar.ColorBase(cax, cmap=sm, orientation='vertical' )
    plt.savefig(VIZ_1_out_file)
    # plt.show()
    # plt.close()

    ####################################################################################################################
    # VIZ_2 and VIZ_3 - freeway commute times and distances tables
    ####################################################################################################################

    # Load the freeway-only validation file
    val_fwy_gdf = spatial_tools.text_to_points_gdf(commute_validation_fwy_file, 'HomeX', 'HomeY', sep='\t', crs=crs_orig)
    val_fwy_gdf.to_crs(crs_new, inplace=True)  # project to WGS84

    ##
    # Spatial join to map points to Counties
    ##
    county_gdf = gpd.read_file(county_file)

    ##
    # Spatial join map points to Counties
    ##
    val_total_gdf_2 = gpd.sjoin(val_total_gdf, county_gdf, how='left', op='within')
    val_fwy_gdf_2 = gpd.sjoin(val_fwy_gdf, county_gdf, how='left', op='within')
    
    ##
    # Aggregate values at County level
    ##
    
    # Freeway
    g_2_fwy = val_fwy_gdf_2.groupby('COUNTY')
    means_2_fwy = g_2_fwy.mean()
    means_2_fwy['COUNTY'] = means_2_fwy.index
    # join with the geometries
    merged_2_fwy = county_gdf.merge(means_2_fwy, how='outer', on='COUNTY')
    
    # Totals
    g_2_total = val_total_gdf_2.groupby('COUNTY')
    means_2_total = g_2_total.mean()
    means_2_total['COUNTY'] = means_2_total.index
    # join with the geometries
    merged_2_total = county_gdf.merge(means_2_total, how='outer', on='COUNTY')
    
    ##
    # Tables
    ##
    
    # Times (TotalH2W from totals, others from the fwy data)
    comm_times = merged_2_total[['TotalTimeH2W', 'COUNTY']].merge(
        merged_2_fwy[['DelayTimeH2W','TimeInCongestionH2W', 'COUNTY']], how='outer', on='COUNTY')
    # comm_times_old = merged_2_fwy[['TotalTimeH2W', 'DelayTimeH2W','TimeInCongestionH2W']]
    comm_times.index = comm_times['COUNTY']
    comm_times.drop('COUNTY', axis=1, inplace=True)
    # Totals
    s = val_fwy_gdf.mean()[['TotalTimeH2W', 'DelayTimeH2W', 'TimeInCongestionH2W']]
    # Replace the TotalTimeH2W with the totals value instead of the fwy value
    s['TotalTimeH2W'] = val_total_gdf.mean()['TotalTimeH2W']
    s.name = 'TOTALS'
    comm_times = comm_times.append(s)
    comm_times = (comm_times/60).round(1)
    comm_times.sort(inplace=True)
    comm_times.index.rename('', inplace=True)
    comm_times.to_csv(VIZ_2_time_out_file, sep='\t')

    # Distances
    comm_dists = merged_2_fwy[['TotalDistH2W', 'DistInCongestionH2W']]
    comm_dists.index = merged_2_fwy['COUNTY']
    s = val_fwy_gdf.mean()[['TotalDistH2W', 'DistInCongestionH2W']]
    s.name = 'TOTALS'
    comm_dists = comm_dists.append(s)
    comm_dists = (comm_dists/1609.344).round(1)
    comm_dists.sort(inplace=True)
    comm_dists.index.rename('', inplace=True)
    comm_dists.to_csv(VIZ_3_dist_out_file, sep='\t')


    ####################################################################################################################
    # VIZ_4 Commute patterns - horizontal stacked bars
    ####################################################################################################################

    ##
    # Calculate the county-to-county h2w commute flows
    ##

    # Get home counties from totals gdf
    val_gdf = spatial_tools.text_to_points_gdf(commute_validation_total_file, 'HomeX', 'HomeY', sep='\t', crs=crs_orig)
    val_gdf.to_crs(crs_new, inplace=True)  # project to WGS84

    val_gdf_4_home = gpd.sjoin(val_gdf, county_gdf, how='left', op='within')
    # val_gdf_4_home.rename(index=str, columns={'COUNTY': 'COUNTY_HOME', 'geometry': 'geometry_home'}, inplace=True)

    # Create a geometry column of work locations
    x_col, y_col = 'WorkX', 'WorkY'
    val_gdf_4_work = spatial_tools.text_to_points_gdf(commute_validation_total_file, x_col, y_col, sep='\t', crs=crs_orig)
    val_gdf_4_work.to_crs(crs_new, inplace=True)
    val_gdf_4_work = gpd.sjoin(val_gdf_4_work, county_gdf, how='left', op='within')

    # Create merged df w/ home and work counties
    merged_4 = pd.DataFrame({'COUNTY_HOME': val_gdf_4_home['COUNTY'],
                             'COUNTY_WORK': val_gdf_4_work['COUNTY'], 'cnt': 1.00})
    # # Scale commuter counts up to the population total
    # merged_4.cnt = np.true_divide(pop_total_commuters, merged_4.cnt.sum())

    # Group by counties and get total counts
    g_4 = merged_4.groupby(['COUNTY_HOME', 'COUNTY_WORK'])
    # NOTE: we lose about 4% here because they are not mapped to any counties
    # Scale commuter counts up to the population totals
    commute_patterns = g_4.sum()
    commute_patterns.cnt = np.true_divide(pop_total_commuters, commute_patterns.cnt.sum())*commute_patterns.cnt
    commute_patterns.cnt = commute_patterns.cnt.round().astype(int)
    commute_patterns.to_csv(VIZ_4_stacked_out_file, sep='\t')
    cp = commute_patterns.unstack()
    cp.to_csv(VIZ_4_tab_out_file, sep='\t')



    ##
    # Build the visualization
    ##

    counties = sorted(cp.index.values)  # sorted list of county names
    cp.sort(inplace=True)
    cp.sort(axis=1, inplace=True)

    # Get the widths of each individual bar in the horizontal stacks
    widths = []
    for i in range(cp.shape[0]):
        # row = [-1 * n for n in cp.iloc[i,:].values.tolist()] + cp.iloc[:,i].values.tolist()[::-1]
        row = cp.iloc[i, :].values.tolist() + cp.iloc[:, i].values.tolist()[::-1]
        row = np.delete(row, [i, len(row) -i - 1])  # delete the self-self flows
        widths.append(row)
    widths = np.array(widths)
    print widths

    # Calc left edges of each bar
    lefts = []
    for i in range(cp.shape[0]):
        left = [-1*np.sum(widths[i, 0:widths.shape[1]/2])]
        left = np.append(left, left[0] + np.cumsum(widths[i, 0:-1]))
        #
        # for j in np.arange(widths.shape[1] - 1):
        #     left.append(left[j] + widths[i, j])
        lefts.append(left)
    lefts = np.array(lefts)

    # Define colors for each bar. Skips the colors for self-self flows
    cmmap_name = 'Set1'
    cmap = plt.get_cmap(cmmap_name)
    all_colors = [cmap(i) for i in np.linspace(0, 1, cp.shape[0])]
    all_colors = all_colors + all_colors[::-1]
    colors = [np.array(all_colors[1:-1])]
    for i in range(1, cp.shape[0]):
        c_left = all_colors[0:i]
        c_mid = all_colors[i+1: -i -1]
        c_right = all_colors[-i:]
        colors.append(np.array(c_left + c_mid + c_right))
    colors = np.array(colors)

    # Build the stacked horizontal bar plot
    pos = -1*np.arange(cp.shape[0]) - 0.5
    fig = plt.figure(figsize=(16, 9))
    plts = []
    for i in np.arange(widths.shape[1]):
    # for i in np.arange(3):
        p = plt.barh(pos, widths[:, i], left=lefts[:, i], color=colors[:, i, :], alpha=0.5)
        plts.append(p)
    # patches = [p[0] for p in plts]
    patches = [plts[i].patches[i+1] for i in np.arange(cp.shape[0]-1)]
    patches.append(plts[cp.shape[0]].patches[0])
    #
    # face_colors = [plts[i].patches[i+1].get_facecolor() for i in np.arange(cp.shape[0]-1)]
    # face_colors.append(plts[cp.shape[0]-1].patches[0].get_facecolor())
    plt.legend(patches[0:9], counties, bbox_to_anchor=(0.0, -0.15, 1.0, .102), loc=3,
               ncol=5, mode="expand", borderaxespad=0.0, fontsize=15)
    plt.yticks(pos+0.4, counties, fontsize=20)
    # Fix the axis is barx_min set
    # if barx_min:
    #     ox = plt.axis()
    #     plt.axis([barx_min, barx_max, ox[2], ox[3]])
    ax = fig.axes[0]
    ax.get_xaxis().set_major_formatter(matplotlib.ticker.FuncFormatter(lambda x, b: format(int(x), ',')))
    # ax.set_xticklabels(ax.get_xticklabels(), fontsize=20)
    ax.set_title(comm_title, fontsize=25)
    plt.setp(ax.get_xticklabels(), fontsize=20)
    plt.savefig(VIZ_4_bars_out_file, bbox_inches='tight')
    # plt.show()
    # plt.close()






















