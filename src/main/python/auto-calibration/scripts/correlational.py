# Author: Kiran Chhatre
import os, subprocess, time, glob, csv, shutil
import pandas as pd
from modify_csv import modify_csv
from config import *
from worker import ext_change, change_conf

# KEEP ALL INTERCEPTS AS ZERO and KEEP OUTPUT FOLDER EMPTY!!

# Deleting shared o/p folder contents
if not os.path.exists(shared):
    os.makedirs(shared)
else:
    file_list = [f for f in os.listdir(shared)]
    for f in file_list:
        os.remove(os.path.join(shared, f))

################################### Preprocessing

num = 1
shutil.copy(base_urbansim_config, copy_urbansim_config % (num))
picked_conf_file = copy_urbansim_config % (num)   # label the file
filename = copy_urbansim_txt % (num)
input_vector = [0, 0, 0, 0, 0, 0, 0, 0, 1]
ext_change('edit', picked_conf_file, filename)
change_conf(input_vector, filename)
ext_change('save', picked_conf_file, filename)

################################### Fire BEAM
os.chdir(beam)
subprocess.call(['./gradlew', ':run', f"-PappArgs=['--config', '{picked_conf_file}']"])
os.chdir(search_space)

################################### Bookkeeping phase

out_dir = glob.glob(sf_light_dir)
out_file = out_dir[0]+'/referenceRealizedModeChoice.csv'
while not os.path.exists(out_file):
    time.sleep(1)

df = pd.read_csv(out_file)

df.loc[1, 'iterations'] = 'modeshare_now'
del df['cav']

car_speed = out_dir[0]+'/CarSpeed.csv'
free_flow_car_speed = out_dir[0]+'/FreeFlowCarSpeed.csv'
car_speed_df = pd.read_csv(car_speed)
car_speed_avg=car_speed_df[(car_speed_df["iteration"] == finaliteration) | (car_speed_df["carType"] == 'Personal')]['avg'].values[0]
car_speed_df = pd.read_csv(free_flow_car_speed)
free_flow_car_speed_avg=car_speed_df[(car_speed_df["iteration"] == finaliteration_flowcapacity) | (car_speed_df["carType"] == 'Personal')]['avg'].values[0]
df['speed'] = [free_flow_car_speed_avg, car_speed_avg]

input_vector.insert(0, "intercepts_now")
df.loc[-1] = input_vector
df.index = df.index+1
df.sort_index(inplace=True)
df.set_index('iterations', inplace=True)
df.loc['L1'] = df.loc['benchmark'] - df.loc['modeshare_now']
df.loc['L1_rank'] = df.loc['L1'].abs().rank(ascending=False)
df.loc['Positive_Directionality'] = df.loc['L1'].ge(0)
total_L1 = df.loc['L1'].abs().sum()

# intercepts_now, benchmark, modeshare_now, L1, L1_rank, Positive_Directionality
df.to_csv(output_csv % (1, total_L1), sep='\t', encoding='utf-8')
csv_name = output_csv % (1, total_L1)
modify_csv(csv_name=csv_name)

