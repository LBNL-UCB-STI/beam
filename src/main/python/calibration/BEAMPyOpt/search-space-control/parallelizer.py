# Author: Kiran Chhatre
# Implementation 2 related
from multiprocessing import Process
import random, time, psutil, logging, sys, multiprocessing, subprocess, glob, os, fnmatch
from worker import fire_BEAM, bookkeep, recipe
from config import *

#sys.stdout=open("test.txt","w")     # if required to export the outputs to a file

# clearing writecue/ firecue file contents
open(writecue, 'w').close()
open(firecue, 'w').close()

BEAM_procs = []
bookkeeping_procs = []
recipe_procs = []

#Shared time-now-list
manager = multiprocessing.Manager()

# information inline to the info fed in the worker
rel_nudge_stages = list(range(init_runs,total_rel_nudge_trials+1, parallel_run))
#rel_nudge_stages = list(range(init_runs,total_rel_nudge_trials+1, 4))   # total init random runs = 8

o = Process(name='recipe-proc', target=recipe)
o.start()
recipe_procs.append(o)

for k in range(len(rel_nudge_stages)): # per stage start x=(number of parallel pass 7 or 4) and 1(for bookkeeping) procs

    while True:
        while True:
            if os.path.getsize(beam+"/writecue.txt") > 0:
                break
        with open(beam+"/writecue.txt", 'r') as fin:
            file_text=fin.readlines()
        time.sleep(10)
        print('Waiting for the write cue...')
        if file_text[0] == 'write stage '+str(k+1)+' done':
            break

    if k == 0:
        parallel_passes = list(range(init_runs-1))                      # total init random runs = 7
    else:
        ##parallel_passes = list(range(4))
        parallel_passes = list(range(parallel_run))

    for m in range(len(parallel_passes)):
        #multiprocessing.log_to_stderr(logging.DEBUG)
        if len(parallel_passes) == init_runs-1:                         # total init random runs = 7
            which_conf = int(m + 2)
        else:
            which_conf = int(rel_nudge_stages[k] - m)
        print('fire_BEAM method initialized at stage '+str(k+1)+'.'+str(m+1))
        p = Process(name='fire-BEAM-'+str(k+1)+'.'+str(m+1), target=fire_BEAM, args=(which_conf,))
        p.start()
        BEAM_procs.append(p)
        time.sleep(1)
    print('All BEAM runs for stage '+str(k+1)+' has been fired!')
    with open(beam+"/firecue.txt", "w") as text_file:
        text_file.write('fire '+str(k+1)+' done')

    print('Bookkeeping method initialized at stage '+str(k+1)+'!')
    q = Process(name='bookkeep-'+str(k+1), target=bookkeep, args=(int(k+1),))
    q.start()
    bookkeeping_procs.append(q)

'''
for filename in glob.glob(beam+'/test/input/sf-light/urbansim-10k_*'):  
    os.remove(filename)
'''

for o in recipe_procs:
    o.join()

for p in BEAM_procs:
    p.join()

for q in bookkeeping_procs:
    q.join()

#sys.stdout.close()     # if required to export the outputs to a file

