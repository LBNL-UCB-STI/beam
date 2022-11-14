#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import os
import sys
import zlib


pd.set_option('display.max_rows', None)
pd.set_option('display.max_columns', None)
pd.set_option('display.width', None)
pd.set_option('display.max_colwidth', None)


def read_csv(file_path):
    try:
        df = pd.read_csv(file_path)
        return df
    except Exception as ex:
        print(f"An exception while reading {file_path}: \n\t{str(ex)}")
        return pd.DataFrame()


def find_and_read_akka_events(base_path, iteration, max_number=100):
    dfs = []

    for i in range(1, max_number):
        actor_events_path = f"{base_path}/ITERS/it.{iteration}/{iteration}.actor_messages_{i}.csv.gz"

        df = read_csv(actor_events_path)
        if len(df) > 0:
            dfs.append(df)
        else:
            break

    print(f"{len(dfs)} chunks were read.")
    if len(dfs) > 0:
        actor_events = pd.concat(dfs)
        return actor_events.fillna("")
    else:
        print("No chunks - no events")
        return False
    
    
    
def read_beam_events(base_path, iteration):        
    base_path = f"{base_path}/ITERS/it.{iteration}/{iteration}"
    events_path =  f"{base_path}.events.csv"
    if os.path.isfile(events_path):
        return read_csv(events_path)
    
    events_path =  f"{base_path}.events.csv.gz"
    if os.path.isfile(events_path): 
        return read_csv(events_path)
        
    raise Exception(f"There are no events files in {base_path}")
    

def read_keys_from_full_config(base_path):
    config_keys = ["simulationName", "replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable"]
    config_map = {}
    
    full_config_path = f"{base_path}/fullBeamConfig.conf"
    with open(full_config_path) as file:
        for line in file:
            for key in config_keys:
                if key in line:
                    config_val = line.strip().split('=')[-1]
                    old_val = config_map.get(key,"")
                    if old_val:
                        config_map[key] = f"{old_val}; {config_val}"
                    else:
                        config_map[key] = config_val
    return config_map

def read_corrupted_file(filename, CHUNKSIZE=1024):
    d = zlib.decompressobj(zlib.MAX_WBITS | 32)
    with open(filename, 'rb') as f:
        result_str = ''
        buffer = f.read(CHUNKSIZE)
        try:
            while buffer:
                result_str += d.decompress(buffer).decode('utf-8')
                buffer = f.read(CHUNKSIZE)
        except Exception as e:
            print('Error: %s -> %s' % (filename, e))
        return result_str

    
def fix_corrupted_gzip_file(file_path, fixed_copy_file_path = ""):
    if not fixed_copy_file_path:
        if file_path.endswith('.csv.gz'):
            fixed_copy_file_path = file_path + "-fixed.csv"
        else:
            fixed_copy_file_path = file_path + "-fixed" + file_path.split('.')[-1]
    file_content = read_corrupted_file(file_path)
    with open(fixed_copy_file_path, "w") as text_file:
        text_file.write(file_content)
    print(f"the content of '{file_path}' is read and \nwritten to '{fixed_copy_file_path}'")

    
print("init complete")


# In[ ]:


# https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/sfbay/gemini-oakland-helics__2022-10-31_13-51-20_yzq
# https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/sfbay/gemini-oakland-helics__2022-10-31_15-23-47_aer
# https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/sfbay/gemini-oakland-helics__2022-11-01_08-54-54_ohh

sim_name = "gemini-oakland-helics__2022-10-31_13-51-20_yzq"
aws_base_path = f"https://beam-outputs.s3.amazonaws.com/output/sfbay/{sim_name}"
dir_name = f"output1_{sim_name}"
events_aws_path = f"{aws_base_path}/ITERS/it.0/0.events.csv.gz"
beam_log_aws_path = f"{aws_base_path}/beamLog.out"

# !mkdir $dir_name
# !wget $events_aws_path 
# !mv 0.events.csv.gz $dir_name
# !wget $beam_log_aws_path 
# !mv beamLog.out $dir_name

get_ipython().system('ls *')
print("")

log_path = f"{dir_name}/beamLog.out"
get_ipython().system('tail -77 $log_path | head -10')


# In[ ]:


akka_events = find_and_read_akka_events(aws_base_path, 0, 18)
print("the len is", len(akka_events))
akka_events.tail(3)


# In[ ]:


df1 = akka_events[(akka_events['sender_name'] == '6102200') | (akka_events['receiver_name'] == '6102200')]
triggers = set(df1['triggerId'])
triggers.remove(-1)
df2 = akka_events[(akka_events['sender_name'] == '6102200') | (akka_events['receiver_name'] == '6102200') | (akka_events['triggerId']).isin(triggers)]
print('the number of triggers:', len(triggers), "the len of df1:", len(df1), "the len of df2:", len(df2))


# In[ ]:


df2.tail(20)


# In[ ]:


base_output_path = '../../output/beamville'

subfolders = sorted([ f.path for f in os.scandir(base_output_path) if f.is_dir() ])
for subfolder in subfolders:
    print(subfolder)


# In[ ]:


def plot_chosen_events_for_selected_person(base_path):
    events = read_beam_events(base_path, 0)
    chosen_events = events[(events['person'] == '2') & (events['type'].isin(set(['ModeChoice','Replanning'])))]
    chosen_columns = ["person", "time", "type", "mode", "currentTourMode", "length", "availableAlternatives", 
                      "location", "personalVehicleAvailable", "tourIndex", "legModes", "legVehicleIds", "reason"]
    display(chosen_events[chosen_columns])    

for base_path in subfolders:
    config = read_keys_from_full_config(base_path)
    print(f"\n{config['simulationName']}, feature: {config['replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable']}")
    plot_chosen_events_for_selected_person(base_path)


# In[ ]:


actor_events = actor_events.fillna("")
actor_events[actor_events['state'].str.contains('eplanning')]


# In[ ]:


rh_agents = []
for sender in events['sender_name'].unique():
    if 'rideHailAgent' in sender and '-L5' in sender:
        rh_agents.append(sender)
        
print(f"there are {len(rh_agents)} L5 rh vehicles, for example:\n",rh_agents[:7])


# In[ ]:


rh_to_msgs = {}

for rh in rh_agents:
    msgs = events[(events['sender_name'] == rh) | (events['receiver_name'] == rh)]
    rh_to_msgs[rh] = msgs
    print(f"{rh} has {len(msgs)} messages, the last is: {msgs.tail(1).iloc[0,5]}")


# In[ ]:




