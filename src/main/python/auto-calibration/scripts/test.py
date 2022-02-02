import os, shutil
from config import *
from modify_csv import *
from worker import create_conf_copies, vector, ext_change, change_conf, fire_BEAM, start_beam, bookkeep, process_fcf_results

rel_nudge_stages = list(range(init_runs, total_rel_nudge_trials + 1, parallel_run))

for k in range(len(rel_nudge_stages)):
    which_stage = rel_nudge_stages[k]
    print(which_stage)

