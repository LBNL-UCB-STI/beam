# Author: Kiran Chhatre
# Implementation 2 related

import pandas as pd
from relativeFactoredNudges import getNudges
from config import *
from plot_mode import save
from plot_ssc_result import save_ssc_plot
import subprocess, os, shutil, glob, time, fnmatch, multiprocessing, warnings, pickle
from modify_csv import modify_csv
from pandas.core.common import SettingWithCopyWarning

warnings.simplefilter(action="ignore", category=SettingWithCopyWarning)

# NO MECHANISM TO START 0TH ITERATION WITH ALL ZERO INTERCEPTS. Design it accordingly! Current workaround: run correlational_1.py before running parallelizer_1.py

# iterator
rel_nudge_stages = list(range(init_runs, int(total_rel_nudge_trials)+1, parallel_run)) # total init random runs = 8 ; 8, 12, 16, 20, 24, 28, 32, 36


def create_conf_copies(no_iters, which_stage):
    if which_stage == init_runs:                                  # total init random runs = 8
        for num in range(no_iters): # no_iters = 7
            shutil.copy(base_urbansim_config, copy_urbansim_config % (num+2))
    else: # which_stage is 12, 16, 20, 24, 28, 32, 36
        for num in range(no_iters): # no_iters = 4
            shutil.copy(base_urbansim_config, copy_urbansim_config % (which_stage-num))


def ext_change(param, picked_conf_file, filename):
    if param == 'edit':
        os.rename(picked_conf_file, picked_conf_file[:-4] + 'txt')
    elif param == 'save':
        os.rename(filename, filename[:-3] + 'conf')


def start_beam(fcf, step, index, filename):
    fcf_config=flow_capacity_config%(step, index)
    shutil.copy(filename, fcf_config)
    with open(fcf_config, 'r') as fin:
        file_text = fin.readlines()

    with open(fcf_config, 'w') as fini:
        for line in file_text:
            # Repairing the lastiteration value of the conf file
            if 'lastIteration' in line:
                line = line.split('=', 1)[0]+'=' + finaliteration_flowcapacity + '\n'

            if 'flowCapacityFactor' in line:
                line = line.split('=', 1)[0]+'=' + str(fcf) + '\n'

            fini.write(line)

    os.chdir(beam)
    subprocess.call(['./gradlew', ':run', f"-PappArgs=['--config', '{fcf_config}']"])
    os.chdir(search_space)


def change_conf(input_vector, filename, set_fcf = True, set_last_iter = True):

    with open(filename, 'r') as fin:
        file_text = fin.readlines()

    '''
    MATCHING INDICES FROM MEMORY BANK CSVS TO CONF MODE CHOICES:
    Output CSV:
    bike,car,drive_transit,ride_hail,ride_hail_pooled,ride_hail_transit,walk,walk_transit
    0    1   2             3         4                5                 6    7              
    CONF:
    car,walk_transit,drive_transit,ride_hail_transit,ride_hail,ride_hail_pooled,walk,bike
    0   1            2             3                 4         5                6    7
    Line number in Conf file:
    25  26           27            28                29        30               31   32
    ATTENTION!! file_text index will be: line number in conf - 1
    required input_vector indices:
    1   7            2             5                 3         4                6    0
    '''

    with open(filename, 'w') as fini:
        print(input_vector, filename)
        real_param = [input_vector[1], input_vector[7], input_vector[2], input_vector[5], input_vector[3], input_vector[4], input_vector[6], input_vector[0]]
        for line in file_text:
            # Adding the intercepts values
            if '_intercept' in line:
                item = real_param.pop(0)
                line = line.split('=', 1)[0]+'='+str(item) + '\n'

            # Repairing the lastiteration value of the conf file
            if 'lastIteration' in line and set_last_iter:
                line = line.split('=', 1)[0]+'='+finaliteration + '\n'

            if 'flowCapacityFactor' in line and set_fcf:
                item = input_vector[-1]
                line = line.split('=', 1)[0]+'='+ str(item) + '\n'

            fini.write(line)


def vector(whichCounter):
    input_vector = getNudges(whichCounter)
    required = init_runs-1 if whichCounter == init_runs else parallel_run
    while True:
        if len(input_vector) == required:
            print('Input vector length matched with parallel_passes!')
            break
        else:
            time.sleep(5)
            print('Length of generated input vector is != parallel_passes.')
    return input_vector


def find_op_folder(parallel_passes, neglect=None):
    # ATTENTION!!! Assuming that op folder is empty before the SSC run
    # (but also after correlational, ie op folder empty after correlational)!
    if neglect is None:
        neglect = []
    while True:
        if any([not glob.glob(sf_light_dir), len(glob.glob(sf_light_dir)) < len(neglect)]):
            time.sleep(10)
            print('All output folders have not been generated yet, waiting...')
        else:
            break
    while True:
        output_folders_tmp = glob.glob(sf_light_dir)
        output_folders = [item for item in output_folders_tmp if item not in neglect]
         
        if len(output_folders) == parallel_passes:
            break
        else:
            time.sleep(10)
            print('Stage level output folders are being generated, waiting...'+len(output_folders)+" "+ parallel_passes)
    return output_folders


def process_fcf_results(op_speed, fcf_size, filename, step=0):
    output_folders = get_and_update_output_dir(fcf_process_seq[step])
    speed_map = {}
    for out_dir in output_folders:
        car_speed = out_dir+'/CarSpeed.csv'
        car_speed_df = pd.read_csv(car_speed)
        car_speed_avg = car_speed_df[(car_speed_df["iteration"] == finaliteration_flowcapacity) | (car_speed_df["carType"] == 'Personal')]['avg'].values[0]
        speed_delta = abs(op_speed - car_speed_avg)
        with open(out_dir + '/beam.conf', 'r') as fin:
            file_text = fin.readlines()
            for line in file_text:
                if 'flowCapacityFactor' in line:
                    fcf = float(line.split('=', 1)[1])
        speed_map[speed_delta] = fcf
    print(speed_map)
    min_key = min(speed_map, key=speed_map.get)
    process_for_next(op_speed, speed_map[min_key], fcf_size, step+1, filename)


def process_for_next(op_speed, current_fcf, fcf_size, step, filename):
    if len(fcf_process_seq) > step:
        next_fcf_step = fcf_size / (fcf_process_seq[step] +1)
        for k in range(0, fcf_process_seq[step]):
            if current_fcf == 0:
                next_fcf = next_fcf_step * (k+1)
            elif current_fcf == 1:
                next_fcf = 1 - next_fcf_step * (k+1)
            else:
                next_fcf = (current_fcf-fcf_size) + 2 * next_fcf_step *(k+1)
            start_beam(next_fcf, step, k, filename)
        process_fcf_results(op_speed, next_fcf_step, filename, step)
    else:
        print("best fcf ",current_fcf)

def get_me_ip_vecs(op_folder):
    ip_vec_file=op_folder+'/beam.conf'
    tmp_vecs = []
    with open(ip_vec_file, 'r') as fin:
        file_text=fin.readlines()
        for line in file_text:
            if '_intercept' in line:
                tmp_vecs.append(float(line.split('=', 1)[1]))

    original_order = [1, 7, 2, 5, 3, 4, 6, 0]
    ip_vecs = [item[0] for item in sorted(zip(tmp_vecs, original_order), key=lambda x: x[1])]
    return ip_vecs


def recipe():
    for i in range(len(rel_nudge_stages)):
        if i == 0:
            pass
        else:
            while True:
                time.sleep(10)
                print('Recipe method waiting to validate required number of csv files before calling the relativeFactoredNudges() at stage '+str(i+1)+' ...')
                if any([len(fnmatch.filter(os.listdir(shared), '*.csv')) > rel_nudge_stages[i-1]-1, len(fnmatch.filter(os.listdir(shared), '*.csv')) == rel_nudge_stages[i-1]]):
                    break
        print('Recipe method initialized at stage '+str(i+1)+'!')
        input_vector_now = vector(whichCounter=rel_nudge_stages[i])

        parallel_passes = init_runs-1 if len(input_vector_now) == init_runs-1 else parallel_run
        which_stage = rel_nudge_stages[i]
        create_conf_copies(no_iters=parallel_passes,which_stage=which_stage)

        print('Conf copies created for stage '+str(i+1)+'!')
        for j in range(parallel_passes):
            config_id = j+2 if which_stage == init_runs else which_stage-j
            picked_conf_file = copy_urbansim_config % config_id
            filename = copy_urbansim_txt % config_id
            ext_change('edit', picked_conf_file, filename)
            print('Input vector at stage '+str(i+1)+'.'+str(j+1)+' is:')
            change_conf(input_vector=input_vector_now[j], filename=filename)
            ext_change('save', picked_conf_file, filename)

        print('All conf files ready for stage '+str(i+1)+'!')
        with open(beam+"/writecue.txt", "w") as text_file:
            text_file.write('write stage '+str(i+1)+' done')

        while True:
            print('Checking firecue file content size...')
            while True:
                if os.path.getsize(beam+"/firecue.txt") > 0:
                    break
            with open(beam+"/firecue.txt", 'r') as fin:
                file_text = fin.readlines()
            time.sleep(10)
            print('Waiting for the fire cue...')
            if file_text[0] == 'fire '+str(i+1)+' done':
                break
        print('Complete stage '+str(i+1)+' is fired!')


def fire_BEAM(number):
    print('BEAM fired on '+str(os.getpid())+' PID.', number)
    picked_conf_file = copy_urbansim_config % number   #label the file
    os.chdir(beam)
    subprocess.call(['./gradlew', ':run', f"-PappArgs=['--config', '{picked_conf_file}']"])
    os.chdir(search_space)


def get_and_update_output_dir(parallel_run):
    with open("op_folders.txt", "rb") as fp:
        neglect = pickle.load(fp)
        print("neglect ",len(neglect))
    output_folders = find_op_folder(parallel_run, neglect)
    new_content = output_folders + neglect
    with open("op_folders.txt", "wb") as fp:
        pickle.dump(new_content, fp)
    return output_folders


def get_output_folder(is_init_step):
    if is_init_step:
        output_folders = find_op_folder(init_runs - 1)
        with open("op_folders.txt", "wb") as fp:
            pickle.dump(output_folders, fp)
    else:
        output_folders = get_and_update_output_dir(parallel_run)
    return output_folders


def bookkeep(which_stage):
    output_folders = get_output_folder(which_stage == 1)
    print('Output folder for stage '+str(which_stage)+' are', output_folders)
    for j in range(len(output_folders)):
        out_file = output_folders[j] + '/referenceRealizedModeChoice.csv'
        while not os.path.exists(out_file):
            time.sleep(10)
            print('In bookkeep method: waiting for BEAM output...')
         
        print('Required csv file for bookkeep() found at stage '+str(which_stage)+'.'+str(j))
        df = pd.read_csv(out_file)

        df.loc[1,'iterations'] = 'modeshare_now'
        
        df = df.drop(['cav', 'bike_transit'], axis=1, errors='ignore')
        ip_vecs = get_me_ip_vecs(output_folders[j])
        ip_vecs.append(1)
        pd.set_option('display.max_columns', None)
        print('Input vector for this run ('+str(which_stage)+'.'+str(j)+') was: ', ip_vecs)

        car_speed = output_folders[j]+'/CarSpeed.csv'
        free_flow_car_speed = output_folders[j]+'/FreeFlowCarSpeed.csv'
        car_speed_df = pd.read_csv(car_speed)
        car_speed_avg = car_speed_df[(car_speed_df["iteration"] == finaliteration) | (car_speed_df["carType"] == 'Personal')]['avg'].values[0]
        car_speed_df = pd.read_csv(free_flow_car_speed)
        free_flow_car_speed_avg = car_speed_df[(car_speed_df["iteration"] == finaliteration_flowcapacity) | (car_speed_df["carType"] == 'Personal')]['avg'].values[0]
        df['speed'] = [free_flow_car_speed_avg, car_speed_avg]

        df.loc[-1] = ['intercepts_now'] + ip_vecs
        df.index = df.index+1
        df.sort_index(inplace=True)
        df.set_index('iterations', inplace=True)
        df.loc['L1'] = df.loc['benchmark'] - df.loc['modeshare_now']
        df.loc['L1_rank'] = df.loc['L1', df.columns[:-1]].abs().rank(ascending=False)
        df.loc['Positive_Directionality'] = df.loc['L1'].ge(0)
        total_L1 = df.loc['L1'].abs().sum()

        if which_stage == 1:
            df.to_csv(output_csv % (j+2, total_L1), sep='\t', encoding='utf-8')
            csv_name = output_csv % (j+2, total_L1)
            modify_csv(csv_name=csv_name)
        else:
            offset_labeling_list = list(range(init_runs-1,total_rel_nudge_trials+1,3))          # total init random runs = 7
            iter_label = offset_labeling_list[which_stage-2] + int(which_stage) + j
            df.to_csv(output_csv % (iter_label, total_L1), sep='\t', encoding='utf-8')
            csv_name = output_csv % (iter_label, total_L1)
            modify_csv(csv_name=csv_name)
    save(which_stage)
    if which_stage > 1:
        save_ssc_plot(which_stage)
    print('bookkeep() for stage '+str(which_stage)+' completed!')
