# Author: Kiran Chhatre
# Implementation 2 related

from relativeFactoredNudges import getNudges
from config import *
import pandas as pd
import subprocess, os, shutil, glob, time, fnmatch, multiprocessing, warnings, math, pickle
from modify_csv import modify_csv
from pandas.core.common import SettingWithCopyWarning

warnings.simplefilter(action="ignore", category=SettingWithCopyWarning)

# NO MECHANISM TO START 0TH ITERATION WITH ALL ZERO INTERCEPTS. Design it accordingly! Current workaround: run correlational_1.py before running parallelizer_1.py

# iterator
rel_nudge_stages = list(range(init_runs,total_rel_nudge_trials+1, parallel_run)) # total init random runs = 8 ; 8, 12, 16, 20, 24, 28, 32, 36

# constants
finaliteration = '0'

p = 24 # intercepts
q = 13 # last iterations
r = 77
# Methods

def create_conf_copies(no_iters, which_stage):
    if which_stage == init_runs:                                               # total init random runs = 8 
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

def change_conf(input_vector, capacity_input_vector, filename):
    with open(filename, 'r') as fin:
        file_text=fin.readlines()

    # Adding the intercepts values

    for i in range(p,p+8,1):
        file_text[i] = file_text[i].split('=',1)[0]+'= '

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

    file_text[p]   = file_text[p]  +str(input_vector[1]) #car_intercept
    file_text[p+1] = file_text[p+1]+str(input_vector[7]) #walk_transit_intercept
    file_text[p+2] = file_text[p+2]+str(input_vector[2]) #drive_transit_intercept
    file_text[p+3] = file_text[p+3]+str(input_vector[5]) #ride_hail_transit_intercept
    file_text[p+4] = file_text[p+4]+str(input_vector[3]) #ride_hail_intercept
    file_text[p+5] = file_text[p+5]+str(input_vector[4]) #ride_hail_pooled_intercept
    file_text[p+6] = file_text[p+6]+str(input_vector[6]) #walk_intercept
    file_text[p+7] = file_text[p+7]+str(input_vector[0]) #bike_intercept

    for j in range(p,p+8,1):
        file_text[j] = file_text[j]+' \n'

    # Repairing the lastiteration value of the conf file

    for i in range(q,q+1,1):
        file_text[i] = file_text[i].split('=',1)[0]+'= '

    file_text[q] = file_text[q]+finaliteration

    for j in range(q,q+1,1):
        file_text[j] = file_text[j]+' \n'

    with open(filename, 'w') as fini:
        for i in file_text:
            fini.write(i)

def vector(whichCounter):
    input_vector = getNudges(whichCounter)
    if whichCounter == init_runs:                               # total init random runs = 8 
        required = init_runs-1                                    # total init random runs = 7
    else:
        required = parallel_run
    while True:
        if len(input_vector) == required:
            print('Input vector length matched with parallel_passes!')
            break
        else:
            time.sleep(5)
            print('Length of generated input vector is != parallel_passes.')
    return input_vector



def find_op_folder(parallel_passes, neglect=[]):
    # ATTENTION!!! Assuming that op folder is empty before the SSC run (but also after correlational, ie op folder empty after correlational)!
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
            print('Stage level output folders are being generated, waiting...')
    return output_folders

def get_me_ip_vecs(op_folder):
    ip_vec_file=op_folder+'/beam.conf'
    tmp_vecs = []
    with open(ip_vec_file, 'r') as fin:
        file_text=fin.readlines()
    for i in range(24,24+8):
        tmp_vecs.append(float(file_text[i].split('=',1)[1]))
    original_order = [1, 7, 2, 5, 3, 4, 6, 0]
    ip_vecs = [item[0] for item in sorted(zip(tmp_vecs, original_order), key=lambda x: x[1])]
    return ip_vecs

# Recipe

def recipe():
    import os
    name = multiprocessing.current_process().name
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
        if len(input_vector_now) == init_runs-1: # [[...],[...],[...],[...],[...],[...],[...]]  # total init random runs = 7
            parallel_passes = init_runs-1                                                       # total init random runs = 7
        else: # len(input_vector_now) == 4
            parallel_passes = parallel_run

        which_stage = rel_nudge_stages[i]

        create_conf_copies(no_iters=parallel_passes,which_stage=which_stage)
        print('Conf copies created for stage '+str(i+1)+'!')
        for j in range(parallel_passes):
            if which_stage == init_runs:                                                        # total init random runs = 8
                picked_conf_file = copy_urbansim_config % (j+2)
                filename = copy_urbansim_txt % (j+2)
                ext_change('edit', picked_conf_file, filename)
            else:
                picked_conf_file = copy_urbansim_config % (which_stage-j)
                filename = copy_urbansim_txt % (which_stage-j)
                ext_change('edit', picked_conf_file, filename)
            print('Input vector at stage '+str(i+1)+'.'+str(j+1)+' is:')
            print(input_vector_now[j])
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
                file_text=fin.readlines()
            time.sleep(10)
            print('Waiting for the fire cue...')
            if file_text[0] == 'fire '+str(i+1)+' done':
                break
        print('Complete stage '+str(i+1)+' is fired!')


def fire_BEAM(number):
    print('BEAM fired on '+str(os.getpid())+' PID.')
    picked_conf_file = copy_urbansim_config % number   # label the file
    os.chdir(beam)
    subprocess.call(['./gradlew', ':run', f"-PappArgs=['--config', '{picked_conf_file}']"])
    os.chdir(search_space)

def bookkeep(which_stage):
    import os
    name = multiprocessing.current_process().name
    if which_stage == 1:
        how_many = init_runs-1                                                            # total init random runs = 7
        output_folders = find_op_folder(parallel_passes=how_many,neglect=[])
        with open("op_folders.txt", "wb") as fp:
            pickle.dump(output_folders, fp)
    else:
        how_many = parallel_run
        with open("op_folders.txt", "rb") as fp:
            neglect = pickle.load(fp)
        output_folders = find_op_folder(parallel_passes=how_many,neglect=neglect)
        new_content = output_folders + neglect
        with open("op_folders.txt", "wb") as fp:
            pickle.dump(new_content, fp)
    print('Output folder for stage '+str(which_stage)+' are', output_folders)
    for j in range(len(output_folders)):
        out_file = output_folders[j] + '/referenceRealizedModeChoice.csv'
        while not os.path.exists(out_file):
            time.sleep(10)
            print('In bookkeep method: waiting for BEAM output...')
        print('Required csv file for bookkeep() found at stage '+str(which_stage)+'.'+str(j))
        if os.path.isfile(out_file):
            time.sleep(2)
            df = pd.read_csv(out_file)
        else:
            raise ValueError("%s isn't a file!" % file_path)
        df['iterations'][1] = 'modeshare_now'
        df = df.drop(['cav','bike_transit'], axis=1, errors='ignore')
        ip_vecs = get_me_ip_vecs(output_folders[j])
        pd.set_option('display.max_columns', None)
        print('Input vector for this run ('+str(which_stage)+'.'+str(j)+') was: ', ip_vecs)
        df.loc[-1] = ['intercepts_now'] + ip_vecs
        df.index = df.index+1
        df.sort_index(inplace=True)
        df.set_index('iterations', inplace=True)
        df.loc['L1'] = df.loc['benchmark'] - df.loc['modeshare_now']
        df.loc['L1_rank'] = df.loc['L1'].abs().rank(ascending=False)
        df.loc['Positive_Directionality'] =  df.loc['L1'].ge(0)
        #df.loc['v_dIntercept'] = [0,0,0,0,0,0,0,0,0]
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
    print('bookkeep() for stage '+str(which_stage)+' completed!')