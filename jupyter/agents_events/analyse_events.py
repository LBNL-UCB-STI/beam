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
        df = pd.read_csv(actor_events_path)
        return df
    except Exception as ex:
        print(f"An exception while reading {actor_events_path}: \n\t{str(ex)}")
        return pd.DataFrame()


def find_and_read_akka_events(base_path, iteration):
    dfs = []

    for i in range(0,100):
        actor_events_path = f"{base_path}/ITERS/it.{iteration}/{iteration}.actor_messages_{i}.csv.gz"
        if not os.path.isfile(actor_events_path): 
            break

        df = read_csv(actor_events_path)
        if len(df) > 0:
            dfs.append(df)

    print(f"There were {len(dfs)} chunks")
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




