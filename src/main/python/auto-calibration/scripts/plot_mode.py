import glob, itertools
import pandas as pd
from natsort import natsorted
from operator import itemgetter
import matplotlib.pyplot as plt
from config import *

# modes:      bike car drive_transit ride_hail ride_hail_pooled ride_hail_transit walk walk_transit
# intercepts: -12  1.5	    6.25	      0.5	       -7.5	           0	      14.25	   4.75       @L1 = 6.01
# benchmark	2	49	4	3	2	1	22	17


mode = ['bike', 'car', 'drive_transit', 'ride_hail', 'ride_hail_pooled', 'ride_hail_transit', 'walk', 'walk_transit']
ideal_intercepts = [-12, 1.5, 6.25, 0.5, -7.5, 0, 14.25, 4.75]


def save(step):
    op_folder = f'{shared}/'
    files = glob.glob(op_folder + '*')
    total_rel_nudge_trials = len(files)
    rel_nudge_stages = list(range(init_runs, total_rel_nudge_trials + 1, parallel_run))  # total init random runs = 8
    names = [file[len(op_folder):-4] for file in files]
    sorted_list = natsorted(names, key=lambda x: x.split('_')[0])  # sort with iter number

    lst1, lst2, lst3, lst4, lst5, lst6, lst7, lst8 = ([] for i in range(8))
    results = [lst1, lst2, lst3, lst4, lst5, lst6, lst7, lst8]
    for i in range(8):
        for j in range(len(sorted_list)):
            df = pd.read_csv(op_folder + sorted_list[j] + '.csv')
            results[i].append(df.loc[0, mode[i]])

    s = []
    for i in range(len(rel_nudge_stages)):
        times = init_runs if i == 0 else rel_nudge_stages[i] - rel_nudge_stages[i - 1]
        s.append([i] * times)  # stage counts for plotting
    merged = list(itertools.chain(*s))

    if total_rel_nudge_trials % 2 != 0:
        merged.insert(len(merged), len(rel_nudge_stages) + 1)  # only for experiment with odd total iters

    # Marking best performance iter
    s1 = []
    for i in range(len(rel_nudge_stages)):
        times = init_runs if i == 0 else rel_nudge_stages[i] - rel_nudge_stages[i - 1]
        s1.append([i] * times)
    merged1 = list(itertools.chain(*s1))

    sorted_list2 = [sorted_list[i].split('_')[1] for i in range(len(sorted_list))]
    sorted_list2 = [float(i) for i in sorted_list2]

    min_index = int(min(enumerate(sorted_list2), key=itemgetter(1))[0])  # for all 48

    fig = plt.figure()
    fig.set_size_inches(18.5, 10.5)
    fig.suptitle('Experiment 21: MNL inspired, optimized')

    plt.subplot(2, 4, 1)
    plt.scatter(merged, lst1, label="bike 2%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[0]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[0], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 2)
    plt.scatter(merged, lst2, label="car 49%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[1]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[1], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 3)
    plt.scatter(merged, lst3, label="drive transit 4%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[2]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[2], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 4)
    plt.scatter(merged, lst4, label="ridehail 3%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[3]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[3], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 5)
    plt.scatter(merged, lst5, label="ridehail pool 2%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[4]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[4], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 6)
    plt.scatter(merged, lst6, label="ridehail transit 1%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[5]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[5], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 7)
    plt.scatter(merged, lst7, label="walk 22%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[6]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[6], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.subplot(2, 4, 8)
    plt.scatter(merged, lst8, label="walk transit 17%")
    plt.scatter(merged1[min_index],
                pd.read_csv(glob.glob(op_folder + '/' + str(min_index + 1) + '_*.csv')[0]).loc[0, mode[7]], color="r",
                label='best SSC iter')
    plt.axhline(y=ideal_intercepts[7], color='r', linestyle='-', label="manual calibration")
    plt.legend()
    plt.xlabel('iteration')
    plt.ylabel('intercept value')

    plt.savefig(plot_path + '/Exp' + str(step) + '.png', dpi=250)


#save(2)
