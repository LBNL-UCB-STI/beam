#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import json
import os
import pathlib
import shutil

from pyproj import CRS, Transformer


pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


class Scenario():
    def __init__(self, beam_output_path, beam_crs, output_path):
        self.beam_crs = beam_crs
        self.beam_output_path = beam_output_path
        self.out_path = self.prepare_output_folder(output_path)
        
        self.events_file = "events.csv"
        self.events_layer_file = "events_settings.json"
        self.network_layer_file = "network_settings.json"
        self.trajectories_file = "trajectories.csv"
        self.trajectories_layer_file = "trajectories_settings.json"
        self.dynamic_network_layer_file = "dynamic_network_settings.json"
        
        self.events = pd.DataFrame(columns=['LinkId', 'StartTime', 'EndTime', 'Type'])
        self.trajectories = pd.DataFrame(columns=['ObjectId', 'Type', 'ProgressBarType', 'ExitTimeLastLink', 'Path'])
        self.dynamic_network = pd.DataFrame(columns=['LinkId', 'EndTime', 'AnimationSequence'])
        
        self.trajectories_icons = [
            {"Type":"Car", "BackgroundColor":"FFA100", "Label":"Taxis", "Icon":"Diamond" },
            {"Type":"Pedastrian", "BackgroundColor":"FF0021", "Label":"Pedastrian", "Icon":"Triangle"},
            {"Type":"Bus", "BackgroundColor":"7C00FF", "Label":"Public Transportation", "Icon":"Circle"}
        ]
        self.events_icons = [
            {"Icon":"Flashing","Label":"Emergency"},
            {"Icon":"Solid", "Label":"Traffic Jam"}
        ]
    
    
    def log(self, text):
        print(f" -> {text}")
        
    
    def prepare_output_folder(self, output_folder_path):
        out_path = pathlib.Path(output_folder_path).resolve()
        out_path.mkdir(exist_ok=True)

        for filename in os.listdir(out_path):
            file_path = os.path.join(out_path, filename)
            try:
                if os.path.isfile(file_path):
                    os.remove(file_path)
            except Exception as e:
                self.log(f"Failed to delete {file_path}. Reason: {e}")
                
        self.log(f"the output path set to '{out_path}'")
        return out_path
    
    
    def read_network(self):
        in_network_path = self.beam_output_path + "/network.csv.gz"
        in_network = pd.read_csv(in_network_path)

        crs_to = CRS.from_epsg(4326) # the lat lon CRS
        crs_from = CRS.from_epsg(self.beam_crs) # original map crs
        transformer = Transformer.from_crs(crs_from, crs_to)

        def xy_to_latlon(df_row):
            (from_x, from_y) = transformer.transform(df_row['fromLocationX'], df_row['fromLocationY'])
            (to_x, to_y) = transformer.transform(df_row['toLocationX'], df_row['toLocationY'])
            return df_row["linkId"], from_x, from_y, to_x, to_y

        network = pd.DataFrame()
        network_cols = ["linkId", "fromLocationX", "fromLocationY", "toLocationX", "toLocationY"]
        network[network_cols] = in_network.apply(xy_to_latlon, axis=1, result_type="expand")
        network["linkId"] = pd.to_numeric(network["linkId"], downcast='integer')
        
        self.log(f"read network ({len(network.index)}) from '{in_network_path}'")
        self.network = network
        
        
    def read_beam_output(self):
        self.log("reading beam output folder")
        self.read_network()
        
        
    def write_config(self): 
        config = {
            "WindowTitle": "Title from config",
            "SimulationTimeSpeed": 5.0,
            "EndSimulationTime": 1600,
            "Layers": [
                {
                    "LayerName": "Network",
                    "OrderId": 0,
                    "FileName": self.network_layer_file,
                    "Visible": True
                },
                {
                    "LayerName": "Trajectory",
                    "OrderId": 2,
                    "FileName": self.trajectories_layer_file,
                    "Visible": True
                },
                {
                    "LayerName": "Event",
                    "OrderId": 3,
                    "FileName": self.events_layer_file,
                    "Visible": True
                },
                {
                    "LayerName": "DynamicNetwork",
                    "OrderId": 1,
                    "FileName": self.dynamic_network_layer_file,
                    "Visible": True
                }
            ]
        }
        config_path = str((self.out_path / "config.json").resolve())
        with open(config_path, "w") as file:
            file.write(json.dumps(config))
    
    
    def write_network_with_settings(self):
        network_path = (self.out_path / "network.csv").resolve()
        self.network.to_csv(network_path, index=False)
        self.log(f"network written to '{network_path}'")

        network_settings = { "NetworkWidth":3, "NetworkColor":"0044EE", "FileName":"network.csv" }
        network_settings_path = str((self.out_path / self.network_layer_file).resolve())
        with open(network_settings_path, "w") as file:
            file.write(json.dumps(network_settings))
        self.log(f"network settings written to {network_settings_path}")

        
    def write_dynamic_network_with_settings(self):
        network_path = (self.out_path / "dynamic_network.csv").resolve()
        self.dynamic_network.to_csv(network_path, index=False)
        self.log(f"dynamic network written to {network_path}")

        network_settings = { "FileName":"dynamic_network.csv" }
        network_settings_path = str((self.out_path / self.dynamic_network_layer_file).resolve())
        with open(network_settings_path, "w") as file:
            file.write(json.dumps(network_settings))
        self.log(f"dynamic network settings written to {network_settings_path}")
        
        
    def set_trajectoris(self, PTE_df, pte_to_icon, pte_to_progressbar, icon_settings):

        def path_traversal_to_lastlinktime_path(path_traversal_event):
            links = path_traversal_event['links'].split(',')
            link_travel_time = path_traversal_event['linkTravelTime'].split(',')

            departure = path_traversal_event['departureTime']

            link_enter_time = link_travel_time[:-1]
            link_enter_time.insert(0, departure)

            # path is string f"{enterTime}_{linkId}"
            path = []
            float_travel_times = []
            total_travel_time = 0
            for (link, str_time) in zip(links, link_enter_time):
                travel_time = float(str_time)
                float_travel_times.append(travel_time)
                total_travel_time = round(travel_time + total_travel_time, 2)
                path.append(f"{total_travel_time}_{link}")

            last_or_minimum = max(float(link_travel_time[-1]), min(float_travel_times))
            exit_time_last_link = round(last_or_minimum + total_travel_time, 2)

            return exit_time_last_link, "+".join(path)        
        
        self.trajectories = pd.DataFrame(columns=['ObjectId', 'Type', 'ProgressBarType', 'ExitTimeLastLink', 'Path'])

        self.trajectories['Type'] = PTE_df.apply(pte_to_icon, axis=1)
        self.trajectories['ObjectId'] = PTE_df['vehicle']
        self.trajectories['Occupancy'] = PTE_df.apply(pte_to_progressbar, axis=1)
        self.trajectories[['ExitTimeLastLink', 'Path']] = PTE_df.apply(path_traversal_to_lastlinktime_path, axis=1, result_type="expand")
        
        self.log(f"got {len(self.trajectories.index)} trajectories")
        self.trajectories_icons = icon_settings

        
    def write_trajectories_with_settings(self):
        path_to_output_file = str((self.out_path / self.trajectories_file).resolve())
        self.trajectories.to_csv(path_to_output_file, index=False)
        self.log(f"{len(self.trajectories.index)} trajectories written to {path_to_output_file} ...")

        trajectories_settings = {
            "IconAlignmentType": "Perpendicular",
            "IconZoomScaleFactor": 800,
            "IconConfig": self.trajectories_icons,
            "FileName": self.trajectories_file   
        }


        trajectories_settings_path = str((self.out_path / self.trajectories_layer_file).resolve())
        with open(trajectories_settings_path, "w") as file:
            file.write(json.dumps(trajectories_settings))
        self.log(f"trajectories settings written to {trajectories_settings_path}")

    def write_events_with_settings(self):
        path_to_output_file = str((self.out_path / self.events_file).resolve())
        self.events.to_csv(path_to_output_file, index=False)
        self.log(f"{len(self.events.index)} events written to {path_to_output_file} ...")

        events_settings = {
            "IconZoomScaleFactor":1600,
            "IconConfig": self.events_icons,
            "FileName": self.events_file
        }

        events_settings_path = str((self.out_path / self.events_layer_file).resolve())
        with open(events_settings_path, "w") as file:
            file.write(json.dumps(events_settings))
        self.log(f"events settings written to {events_settings_path}")

        
    def write_scenario(self):
        self.write_config()
        self.write_network_with_settings()
        self.write_dynamic_network_with_settings()
        self.write_trajectories_with_settings()
        self.write_events_with_settings()
        self.log(f"scenario files written to {self.out_path}")

        
    def pack_to_archive(self, archive_type='zip'):
        source = str(self.out_path)
        destination = f"{source}.{archive_type}"
        
        base, name = os.path.split(destination)
        archive_from = os.path.dirname(source)
        archive_to = os.path.basename(source.strip(os.sep))
        
        shutil.make_archive(name, archive_type, archive_from, archive_to)
        shutil.move('%s.%s' % (name, archive_type), destination)
        shutil.rmtree(source)
        
        self.log(f"scenario packed to '{destination}'")

        
# ## create an empty scenario with map and without events\trajectories
# beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
# output_folder_path = "out_" + beam_output.split('/')[-1].split("\\")[-1]
# beam_crs = 26910
# scenario = Scenario(beam_output, beam_crs, output_folder_path)
# scenario.read_beam_output()
# scenario.write_scenario()
# scenario.pack_to_archive()


# ## pack output folder to tar.gz
# out = scenario.out_path.name
# archive_name = f"{out}_rh_passengers.tar.gz"
# ! rm -rf "$archive_name"
# ! rm -rf "$out/.ipynb"*
# ! tar -zcvf "$archive_name" "$out"
# ! ls "$archive_name" -lh


print("initialized")


# In[2]:


### a set of functions to read BEAM events

def read_events(path_to_events_file, event_types=None, nrows=None):
    events_dtype = { 
        'riders' : str,
        'driver' : str,
        'vehicle' : str,
        'person' : str
    }

    event_types_to_read = set()
    filter_by_type = False
    
    if event_types:
        event_types_to_read = set(event_types)
        filter_by_type = True
    
    df_list = []
    chunksize = 10 ** 6
    with pd.read_csv(path_to_events_file, dtype=events_dtype, low_memory=False, chunksize=chunksize, nrows=nrows) as reader:
        for chunk in reader:
            if filter_by_type:
                df = chunk[chunk['type'].isin(event_types_to_read)]
            else:
                df = chunk
                
            df_list.append(df)
    
    events1 = pd.concat(df_list).dropna(axis=1, how='all')
    return events1


def get_events_file_path(beam_output, iteration):
    p1 = (pathlib.Path(beam_output) / f"ITERS/it.{iteration}/{iteration}.events.csv").resolve()
    p2 = (pathlib.Path(beam_output) / f"ITERS/it.{iteration}/{iteration}.events.csv.gz").resolve()
    if p1.is_file():
        return str(p1)
    elif p2.is_file():
        return str(p2)
    else:
        raise Exception(f"Events file does not exist! Not '{str(p1)}' nor '{str(p2)}'")

        
def read_pte_events(beam_output, iteration, nrows=None):
    path_to_events_file = get_events_file_path(beam_output, iteration)                               
    # print(f"reading events from {path_to_events_file} ...")
    ptes = read_events(path_to_events_file, event_types = ["PathTraversal"], nrows=nrows)
    with_links = ptes['links'].notna()
    all_pte = ptes[with_links].copy()
    # print(f"read {len(all_pte)} PathTraversal events")
    return all_pte


print("initialized")


# In[ ]:


## 
## create an empty scenario with map and without events\trajectories
##

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "empty_" + beam_output.split('/')[-1].split("\\")[-1]
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()
scenario.write_scenario()
scenario.pack_to_archive()


# In[3]:


##
## ALL RH events split into 3 groups: without passengers, with 1 passenger, with more passengers
##

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "rh_passengers_" + beam_output.split('/')[-1].split("\\")[-1]
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()

all_pte = read_pte_events(beam_output, 0)
is_rh = all_pte['vehicleType'] == "RH_Car"
all_rh = all_pte[is_rh]

rh_icons = [
    {
        "Type":"RH0",
        "BackgroundColor":"c4c4c4",
        "Label":"RH without passengers",
        "Icon":"Triangle"
    },
    {
        "Type":"RH1",
        "BackgroundColor":"fccf03",
        "Label":"RH with 1 passenger",
        "Icon":"Triangle"
    },
    {
        "Type":"RHM",
        "BackgroundColor":"fc0f03",
        "Label":"RH with more than 1 passenger",
        "Icon":"Triangle"
    }
]

def pte_to_icon_type(path_traversal_event):
    num_passengers = path_traversal_event['numPassengers']
    if num_passengers < 1.0:
        return "RH0"
    elif num_passengers == 1.0:
        return "RH1"
    else:
        return "RHM"

def pte_to_progress_bar(pte):
    return "None"

    
scenario.set_trajectoris(all_rh, pte_to_icon_type, pte_to_progress_bar, rh_icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[5]:


##
## ALL RH events split into 3 groups: withpassengers, dead heading, repositioning
##

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "rh_deadheading_repositioning_" + beam_output.split('/')[-1].split("\\")[-1]
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()

all_pte = read_pte_events(beam_output, 0)
is_rh = all_pte['vehicleType'] == "RH_Car"
all_rh = all_pte[is_rh].copy()

vehicle_to_passengers = {}
# going backwards
for idx, row in all_rh.sort_values('time', ascending=False).iterrows():
    v = row['vehicle']
    all_rh.loc[idx, 'futurePassengers'] = vehicle_to_passengers.get(v, 0)
    vehicle_to_passengers[v] = int(row['numPassengers'])


rh_icons = [
    {
        "Type":"RH_ps",
        "BackgroundColor":"c4c4c4",
        "Label":"RH with passenger(s)",
        "Icon":"Triangle"
    },
    {
        "Type":"RH_dh",
        "BackgroundColor":"fccf03",
        "Label":"RH deadheading",
        "Icon":"Triangle"
    },
    {
        "Type":"RH_rp",
        "BackgroundColor":"fc0f03",
        "Label":"RH repositioning",
        "Icon":"Triangle"
    }
]

def pte_to_icon_type(path_traversal_event):
    now_passengers = path_traversal_event['numPassengers']
    future_passengers = path_traversal_event['futurePassengers']
    if now_passengers == 1.0:
        return "RH_ps"
    elif now_passengers == 0.0 and future_passengers == 1.0:
        return "RH_dh"
    else:
        return "RH_rp"

def pte_to_progress_bar(pte):
    return "None"

    
scenario.set_trajectoris(all_rh, pte_to_icon_type, pte_to_progress_bar, rh_icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[6]:


##
## ALL public transport split into
##

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "bus_passengers_" + beam_output.split('/')[-1].split("\\")[-1] + "_bus_passengers"
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()

all_pte = read_pte_events(beam_output, 0)
pte = all_pte[all_pte['mode'] == 'bus'].copy()

icons = [
    {
        "Type":"BUS0",
        "BackgroundColor":"c4c4c4",
        "Label":"empty bus",
        "Icon":"Triangle"
    },
    {
        "Type":"BUS1",
        "BackgroundColor":"fccf03",
        "Label":"bus with 1 passenger",
        "Icon":"Triangle"
    },
    {
        "Type":"BUS2",
        "BackgroundColor":"fc0f03",
        "Label":"bus with 2 passengers",
        "Icon":"Triangle"
    }
]

def pte_to_icon_type(path_traversal_event):
    passengers = path_traversal_event['numPassengers']
    if passengers == 0.0:
        return "BUS0"
    elif passengers == 1.0:
        return "BUS1"
    else:
        return "BUS2"

def pte_to_progress_bar(pte):
    return "None"

    
scenario.set_trajectoris(pte, pte_to_icon_type, pte_to_progress_bar, icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[8]:


##
## ALL PTE by mode
##

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "bus_car_walk_" + beam_output.split('/')[-1].split("\\")[-1] + "_all_pte_by_mode"
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()

all_pte = read_pte_events(beam_output, 0)

icons = [
    {
        "Type":"BUS",
        "BackgroundColor":"c4c4c4",
        "Label":"any bus",
        "Icon":"Triangle"
    },
    {
        "Type":"CAR",
        "BackgroundColor":"fccf03",
        "Label":"any car",
        "Icon":"Triangle"
    },
    {
        "Type":"WALK",
        "BackgroundColor":"fc0f03",
        "Label":"walk",
        "Icon":"Triangle"
    }
]

def pte_to_icon_type(path_traversal_event):
    mode = path_traversal_event['mode']
    if mode == 'bus':
        return "BUS"
    elif mode == 'car':
        return "CAR"
    else:
        return "WALK"

def pte_to_progress_bar(pte):
    return "None"

    
scenario.set_trajectoris(pte, pte_to_icon_type, pte_to_progress_bar, icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[12]:


##
## PTE with passengers + car 
##

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "with_passengers_only_bus_car_walk_" + beam_output.split('/')[-1].split("\\")[-1] + "_all_pte_by_mode"
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()

all_pte = read_pte_events(beam_output, 0)

single_car_types = set(['BEV','Car','PHEV'])
single_car = all_pte['vehicleType'].isin(single_car_types)
has_passengers = all_pte['numPassengers'] > 0.0
pte = all_pte[single_car | has_passengers].copy()

icons = [
    {
        "Type":"BUS",
        "BackgroundColor":"c4c4c4",
        "Label":"any bus with passengers",
        "Icon":"Triangle"
    },
    {
        "Type":"CAR",
        "BackgroundColor":"fccf03",
        "Label":"RH with passengers or any car",
        "Icon":"Triangle"
    },
    {
        "Type":"WALK",
        "BackgroundColor":"fc0f03",
        "Label":"walk",
        "Icon":"Triangle"
    }
]

def pte_to_icon_type(path_traversal_event):
    mode = path_traversal_event['mode']
    if mode == 'bus':
        return "BUS"
    elif mode == 'car':
        return "CAR"
    else:
        return "WALK"

def pte_to_progress_bar(pte):
    return "None"


scenario.set_trajectoris(pte, pte_to_icon_type, pte_to_progress_bar, icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:




