import os
import glob, itertools
from natsort import natsorted
import matplotlib.pyplot as plt
from operator import itemgetter
from config import *


def save_ssc_plot(step):
    op_folder = f'{shared}/'
    files = glob.glob(op_folder + '*')
    total_rel_nudge_trials = len(files)
    rel_nudge_stages = list(range(init_runs, total_rel_nudge_trials + 1, parallel_run))  # total init random runs = 8


    fig, ax = plt.subplots()

    files = glob.glob(shared + '/*')

    names = [file[len(shared) + 1:-4] for file in files]

    sorted_list = natsorted(names, key=lambda x: x.split('_')[0])
    sorted_list = [sorted_list[i].split('_')[1] for i in range(len(sorted_list))]
    sorted_list = [float(i) for i in sorted_list]

    init_run = sorted_list[1:init_runs]  # total init random runs = 8
    sorted_list1 = sorted_list[init_runs:]  # total init random runs = 8

    init_iter = [0] * (init_runs - 1)  # total init random runs = 7

    s1 = []
    for i in range(len(rel_nudge_stages)):
        times = init_runs if i == 0 else rel_nudge_stages[i] - rel_nudge_stages[i - 1]
        s1.append([i] * times)
    merged1 = list(itertools.chain(*s1))

    s = []
    for i in range(len(rel_nudge_stages)):
        times = 0 if i == 0 else rel_nudge_stages[i] - rel_nudge_stages[i - 1]
        s.append([i] * times)
    merged = list(itertools.chain(*s))

    # Model progress
    combined = [[x, sorted_list1[i]] for i, x in enumerate(merged)]

    model_prog = []
    for k in range(combined[-1][0]):
        least_now = min((x for x in combined[4 * k:(4 * k) + 4]), key=lambda k: k[1])
        model_prog.append(least_now)
    min_so_far = model_prog[0][1]
    model_prog_plot = []
    for x in range(len(model_prog)):
        if model_prog[x][1] <= min_so_far:
            model_prog_plot.append(model_prog[x])
            min_so_far = model_prog[x][1]

    model_prog_plot = [[0, min(sorted_list[:init_runs])]] + model_prog_plot  # total init random runs = 8
    model_prog_plot.append([merged1[-1] + 1, model_prog_plot[-1][1]])
    model_prog_x = [i[0] for i in model_prog_plot]
    model_prog_y = [i[1] for i in model_prog_plot]

    min_index = int(min(enumerate(sorted_list), key=itemgetter(1))[0])  # for all 48
    min_index1 = int(min(enumerate(sorted_list1), key=itemgetter(1))[0])  # for 40

    fig.suptitle('BEAMPyOpt: MNL inpired, optimized')

    plt.step(model_prog_x, model_prog_y, where='post', linestyle='--', color='green', linewidth=0.5,
             label='Model progress')

    plt.scatter(0, sorted_list[0], marker=",", color="y", label="all zero intercepts")
    plt.scatter(init_iter, init_run, color='b', label="initial random 7 runs")

    plt.vlines(merged1[min_index], 7.5, sorted_list[min_index], linestyle="dashed", color='black', linewidth=0.5,
               label=str(sorted_list[min_index] - 7.5) + '%' + ' diff converged @ ' + str(
                   20 + (merged1[min_index] * 10)) + ' mins')

    merged.pop(min_index1)
    sorted_list1.pop(min_index1)
    plt.scatter(merged, sorted_list1, label="BEAMPyOpt")
    plt.scatter(merged1[min_index], sorted_list[min_index], marker="*", color="r",
                label="SSC best performance" + str(int(sorted_list[min_index])) + '% @ ' + str(
                    int(min_index) + 1) + ' iter')

    plt.legend(loc="upper right", fontsize='xx-small')
    plt.xlabel('optimization stage')
    plt.ylabel('Error')
    plt.ylim(ymin=0)

    if not os.path.exists(plot_path):
        os.makedirs(plot_path)
    plt.savefig(plot_path + '/L1_vs_iters' + str(step) + '.png', dpi=500)

#save_ssc_plot(2)
