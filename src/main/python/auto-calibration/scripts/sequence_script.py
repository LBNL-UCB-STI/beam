import os, shutil
from config import *
from modify_csv import *
from worker import create_conf_copies, vector, ext_change, change_conf, fire_BEAM, start_beam, bookkeep, process_fcf_results

rel_nudge_stages = list(range(init_runs, total_rel_nudge_trials + 1, parallel_run))

for k in range(len(rel_nudge_stages)):  # per stage start x=(number of parallel pass 7 or 4) and 1(for bookkeeping) procs

    which_stage = rel_nudge_stages[k]
    input_vector_now = vector(which_stage)
    parallel_passes = init_runs-1 if len(input_vector_now) == init_runs-1 else parallel_run
    create_conf_copies(parallel_passes, which_stage)

    for m in range(parallel_passes):
        which_conf = m + 2 if parallel_passes == init_runs - 1 else (which_stage - m)
        print('fire_BEAM method initialized at stage ' + str(k + 1) + '.' + str(m + 1))
        picked_conf_file = copy_urbansim_config % which_conf
        filename = copy_urbansim_txt % which_conf
        ext_change('edit', picked_conf_file, filename)
        print('Input vector at stage ' + str(k + 1) + '.' + str(m + 1) + ' is:')
        input_vector_now[m].append(1)
        change_conf(input_vector_now[m], filename)
        ext_change('save', picked_conf_file, filename)
        fire_BEAM(which_conf)

    print('All BEAM runs for stage ' + str(k + 1) + ' has been fired!')

    print('Bookkeeping method initialized at stage ' + str(k + 1) + '!')
    bookkeep(k + 1)

print("----------intercept optmimisation done-----------")
number = fcf_process_seq[0]
step = 1 / number
input_vector_file = find_min_csv()
input_vector = find_intercept(input_vector_file)
shutil.copy(base_urbansim_config, base_config)
change_conf(input_vector[: len(input_vector) - 1], base_config, False)

op_speed = input_vector[-1]
for k in range(0, number):
    fcf = step * (k+1)
    start_beam(fcf, 0, k,  base_config)

print(op_speed)
process_fcf_results(op_speed, step, base_config)

