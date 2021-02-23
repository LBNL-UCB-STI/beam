
import glob, itertools, operator
import pandas as pd
from natsort import natsorted
import matplotlib.pyplot as plt
from operator import itemgetter
from config import *

total_rel_nudge_trials = 300  # total iterations count!!
rel_nudge_stages = list(range(16,total_rel_nudge_trials+1,4))     # total init random runs = 8

fig, ax = plt.subplots()

repo = shared
files = glob.glob(repo+'/*')
#files = glob.glob('/home/berkeleylab/test_code_files/plot_results/home/ubuntu/kiran_thesis/beam/src/main/python/calibration/BEAMPyOpt/storage/*')
#df = pd.read_csv(files[0])

names= []
for i in range(len(files)):
    names.append(files[i][len(repo)+1:-4])

sorted_list = natsorted(names, key=lambda x: x.split('_')[0])
sorted_list = [sorted_list[i].split('_')[1] for i in range(len(sorted_list))]
sorted_list = [float(i) for i in sorted_list]

init_run = sorted_list[1:16]         # total init random runs = 8
sorted_list1 = sorted_list[16:]       # total init random runs = 8

init_iter = [0] * 15                  # total init random runs = 7

s1 = []
for i in range(len(rel_nudge_stages)):
    if i == 0:
        times = 16                     # total init random runs = 8
    else:
        times = rel_nudge_stages[i] - rel_nudge_stages[i-1]
    s1.append([i] * times)
merged1 = list(itertools.chain(*s1))

s = []
for i in range(len(rel_nudge_stages)):
    if i == 0:
        times = 0
    else:
        times = rel_nudge_stages[i] - rel_nudge_stages[i-1]
    s.append([i] * times)

merged = list(itertools.chain(*s))
# only for this broken experiment
#merged.insert(len(merged),len(rel_nudge_stages)+1)  ### !!!!! REMOVE THIS !!!!!

# Model progress
model_prog = []
combined = [[x,sorted_list1[i]] for i,x in enumerate(merged)]

for k in range(combined[-1][0]):
    least_now = min((x for x in combined[4*k:(4*k)+4]), key=lambda k:k[1])
    model_prog.append(least_now)
min_so_far = model_prog[0][1]
model_prog_plot = []
for x in range(len(model_prog)):
    if model_prog[x][1] <= min_so_far:
        model_prog_plot.append(model_prog[x])
        min_so_far = model_prog[x][1]

model_prog_plot = [[0,min(sorted_list[:16])]] + model_prog_plot   # total init random runs = 8
model_prog_plot.append([merged1[-1]+1,model_prog_plot[-1][1]])
model_prog_x = [i[0] for i in model_prog_plot]
model_prog_y = [i[1] for i in model_prog_plot]


min_index = int(min(enumerate(sorted_list), key=itemgetter(1))[0]) # for all 48
min_index1 = int(min(enumerate(sorted_list1), key=itemgetter(1))[0]) #  for 40 

fig.suptitle('BEAMPyOpt: MNL inpired, optimized')

plt.step(model_prog_x, model_prog_y, where='post', linestyle='--', color='green', linewidth=0.5, label='Model progress')

plt.scatter(0,sorted_list[0],marker=",",color="y",label="all zero intercepts")
plt.scatter(merged1[min_index],sorted_list[min_index],marker="*",color="r",label="SSC best performance"+str(int(sorted_list[min_index]))+'% @ '+str(int(min_index)+1)+' iter')
plt.scatter(init_iter, init_run, color='b', label="initial random 7 runs") 

plt.vlines( merged1[min_index], 7.5, sorted_list[min_index], linestyle="dashed", color='black', linewidth=0.5,label=str(sorted_list[min_index]-7.5)+'%' +' diff converged @ '+str(20+(merged1[min_index]*10))+' mins')

merged.pop(min_index1)
sorted_list1.pop(min_index1)
print(len(merged))
print(len(sorted_list1)) 
plt.scatter(merged, sorted_list1, label="BEAMPyOpt") 
plt.axhline(y=7.5, color='r', linestyle='--', label="manual calibration 7.5%")
plt.legend(loc="upper right", fontsize = 'xx-small')
plt.xlabel('optimization stage')
plt.ylabel('intercept value')
plt.ylim(ymin=0) 

plt.savefig('L1_vs_iters_21.png', dpi=500)
