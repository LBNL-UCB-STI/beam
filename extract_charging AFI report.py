#!/usr/bin/env python
# coding: utf-8

# In[1]:


# from ggplot import *
# from pyproj import Proj, transform

import numpy as np
import pandas as pd
import gzip
import pdb
# import copy
# import os
# import matplotlib.pyplot as plt

import warnings
warnings.filterwarnings("ignore")


# # Extract data for paper TRP:D

# In[ ]:

#
folder_path = "/home/ubuntu/git/beam/output/"
scenario_name = "AFI-SCHT30050MN-rich__2019-06-27_15-45-05"
scenario_path = scenario_name + "/ITERS/it.0/0.events.csv.gz"


# In[73]:


#path directory
# folder_path = "../../../../output/sf-light/"
# scenario_name = "urbansim-1k__2019-05-27_16-13-51"
# scenario_path = scenario_name + "/ITERS/it.0/0.events.csv"
#read event file
with gzip.open(folder_path+scenario_path) as f:

    all_events = pd.read_csv(f, low_memory=False)

# all_events = pd.read_csv(folder_path+scenario_path)
run = scenario_name


# In[74]:


#ridehail
columns = ["time", "duration", "vehicle", "num_passengers", "length", "start.x", "start.y", 
           "end.x", "end.y", "kwh", "run", "speed", "reposition", "type", "hour"]
rh_df = pd.DataFrame(
    [], columns=columns
)

#data process, extract rideHailVehicle
rhvehicle_events = all_events[all_events['vehicle'].str.contains('rideH')==True]
rhvehicle_events = rhvehicle_events[rhvehicle_events['type'].str.contains('PathTra')==True]

###TODO###
#1. need to specify what "run" is 
# addressed - see below
#2. determine what "reposition" is
# addressed - see below 
###TODO###
try:
    rh_df["time"] = rhvehicle_events["time"]
    rh_df["duration"] = rhvehicle_events["arrivalTime"] - rhvehicle_events["departureTime"]
    rh_df["vehicle"] = rhvehicle_events["vehicle"]
    rh_df["num_passengers"] = rhvehicle_events["numPassengers"]
    rh_df["length"] = rhvehicle_events["length"]
    rh_df["start.x"] = rhvehicle_events["startX"]
    rh_df["start.y"] = rhvehicle_events["startY"]
    rh_df["end.x"] = rhvehicle_events["endX"]
    rh_df["end.y"] = rhvehicle_events["endY"]
    rh_df["kwh"] = rhvehicle_events["fuel"]/3.6e6
    rh_df["run"] = run
    rh_df["speed"] = rhvehicle_events["length"]/1609/(rh_df["duration"]/3600)
    rh_df["reposition"] = False
    rh_df["type"] = "Movement"
    rh_df["hour"] = np.floor(rh_df["time"]/3600).astype("int")
except:
    pdb.set_trace()
# distinguish reposition
for vehicle in rh_df["vehicle"].unique():
    # concat two pd series offset by one index, determine if movement precede to a rh activity
    a = pd.concat(
        [pd.Series([np.nan]).append(
            rh_df[rh_df["vehicle"] == vehicle]["num_passengers"], ignore_index=True
        ), 
         rh_df[rh_df["vehicle"] == vehicle]["num_passengers"].append(
             pd.Series([np.nan]), ignore_index=True
         )], 
        axis=1)
    # set repo movement to True
    rh_df.loc[rh_df[rh_df["vehicle"] == vehicle].iloc[a[(a[0]==0) & (a[1]==0)].index-1, :].index, 
              "reposition"] = True

#filters
rh_df = rh_df[rh_df["start.x"]<-100]
rh_df = rh_df[(rh_df["length"]>0) | (rh_df["num_passengers"]>0)]


# In[75]:


#refuel
columns = ["time", "duration", "vehicle", "num_passengers", "length", "start.x", "start.y", 
           "end.x", "end.y", "kwh", "run", "speed", "reposition", "type", "hour"]
rf_df = pd.DataFrame(
    [],columns=columns
)

#data process, extract refuel
refuel_events = all_events[all_events['type'].str.contains('Refuel')==True]

###TODO###
#1. need to specify what "run" is 
###TODO###

rf_df["time"] = refuel_events["time"]
rf_df["duration"] = refuel_events["duration"]
rf_df["vehicle"] = refuel_events["vehicle"]
rf_df["start.x"] = refuel_events["locationX"]
rf_df["start.y"] = refuel_events["locationY"]
rf_df["kwh"] = refuel_events["fuel"]/3.6e6
rf_df["run"] = run
rf_df["type"] = "Charge"
rf_df["hour"] = np.floor(rf_df["time"]/3600).astype("int")


# In[76]:


#concat ride hail df and refueling df for paper
ev_rh_rf_df = pd.concat([rh_df, rf_df])
ev_rh_rf_df = ev_rh_rf_df.reset_index(drop=True)


# # Events and Locations

# In[77]:


# ggplot(ev_rh_rf_df[ev_rh_rf_df["type"]=="Movement"], aes(x="start.x", y="start.y"))         + geom_point(alpha=0.8,size=25)+geom_point(data=ev_rh_rf_df[ev_rh_rf_df["type"]=="Charge"],size=20,color='red')


# # Demand

# In[78]:


print("--------------------------")
print("Scenario: " + scenario_name)
print("Total electricity demand is: " + str(sum(ev_rh_rf_df["kwh"])) + "kWh.")
print("Number of charging events is: " + str(len(ev_rh_rf_df[ev_rh_rf_df["type"]=="Charge"])))
print("Total charging duration is: " + str(sum(ev_rh_rf_df[(ev_rh_rf_df["type"]=="Charge")]["duration"]))+" units.")


# In[79]:


# save file
ev_rh_rf_df.to_csv(run+"_ev_rh_rf_df.csv", index=False)
