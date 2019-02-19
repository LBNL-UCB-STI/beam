import ConfigParser
import os
import sys

import matplotlib
from matplotlib.colors import LogNorm
from matplotlib.colors import SymLogNorm
import numpy as np
import pandas as pd
import pylab
import scipy
import scipy.cluster.hierarchy as sch

__author__ = 'Andrew A Campbell'
# Does the same thing as deviation_clustering.py, but also runs hierarchical clustering on the days.

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    # Paths
    ssqd_dev_path = conf.get('Paths', 'ssqd_dev_path')  # Path to the csv with Sum of Squared Deviations
    img_file = conf.get('Paths', 'img_file')

    # Parameters
    # start_date = conf.get('Params', 'start_date')
    filter_stations = [x.strip() for x in conf.get('Params', 'filter_stations').split(',')]

    # If we want, run deviation_analysis.py to get the new deviation matrix.
    exec_file =  conf.get('Deviation_Analysis', 'exec_file')
    if exec_file:
        print "Running Deviation Anaylsis"
        execfile(exec_file)

    # Create the distance matrix
    print "Creating distance matrix"
    df = pd.read_csv(ssqd_dev_path)
    df.dropna(axis=1, inplace=True)  # Drop any sensors with missing data
    df.drop(filter_stations, axis=1)  # Filter outWriter stations on the filter list in the config file. These are bad outliers
    dat = df.iloc[:, 1:-1].values.transpose()  # Drop the first column, 'Date', and last column (the row totals)
    D = scipy.spatial.distance.cdist(dat, dat, "euclidean")  # Square distance matrix. n x n, n = number of sensors
    D_days = scipy.spatial.distance.cdist(dat.transpose(), dat.transpose(), "euclidean")

    # Run the hierarchical clustering
    print "Running hierarchical clustering"
    Y = sch.linkage(D, method='centroid')
    Y_days = sch.linkage(D_days, method='centroid')

    # Create the dendrograms and plot
    print "Building figures"
    Z_all = sch.dendrogram(Y, distance_sort='ascending', no_plot=True)  # non-plotting dg. Used for resorting the sensors in the heat map
    Z_all_days = sch.dendrogram(Y_days, distance_sort='ascending', no_plot=True)  # non-plotting dg. Used for resorting the sensors in the heat map

    fig = pylab.figure(figsize=(15, 8))

    # Plot the days dendrogram
    ax1 = fig.add_axes([0.09, 0.1, 0.2, 0.6])
    Z_days = sch.dendrogram(Y_days, distance_sort='ascending', orientation='right')
    ax1.set_xticks([])
    ax1.set_yticks([])

    # Plot the sesnors dendrogram
    ax2 = fig.add_axes([0.3, 0.71, 0.6, 0.2])
    Z = sch.dendrogram(Y, p=10, truncate_mode='lastp', distance_sort='ascending')
    # Z = sch.dendrogram(Y, p=5, truncate_mode='level', distance_sort='ascending')
    ax2.set_xticks([])
    ax2.set_yticks([])

    #TODO - Rearrange the dat along both the days and sensors dimensions before matshow

    # Create the heat map
    # fig = pylab.figure(figsize=(12, 8))
    axmatrix = fig.add_axes([0.3, 0.1, 0.6, 0.6])
    # im = axmatrix.matshow(dat[Z_all['leaves']], aspect='auto', origin='lower', cmap=pylab.cm.YlGnBu)
    # norm = matplotlib.colors.Normalize(vmin=np.min(dat), vmax=np.max(dat))
    im = axmatrix.matshow(dat[Z_all['leaves']].transpose()[Z_all_days['leaves']], aspect='auto', origin='lower',
                          norm=SymLogNorm(linthresh=0.03, linscale=5,
                                           vmin=np.round(np.min(dat), 0),
                                           vmax=np.min(np.max(dat), 0)))#, cmap=pylab.cm.bwr)
    # im = axmatrix.matshow(dat[Z_all['leaves']].transpose(), aspect='auto', origin='lower',
    #                       norm=SymLogNorm(linthresh=0.03, linscale=5,
    #                                       vmin=np.round(np.min(dat), 0),
    #                                       vmax=np.min(np.max(dat), 0)))#, cmap=pylab.cm.bwr)

    axmatrix.set_xticks([])
    axmatrix.set_xticks([])
    axmatrix.set_yticks([])

    # Plot colorbar
    rng = np.max(dat) - np.min(dat)
    mn = np.round(np.min(dat), 0)
    mx = np.round(np.max(dat), 0)
    mn_plus = np.round(mn + rng/9.25, 0)
    mx_minus = np.round(mx - rng/1.3, 0)
    c = [np.min(dat), mn_plus, 0, mx_minus, np.max(dat)]
    axcolor = fig.add_axes([0.91, 0.1, 0.02, 0.6])
    pylab.colorbar(im, cax=axcolor, extend='both', ticks=c)
    fig.show()
    fig.savefig(img_file)











