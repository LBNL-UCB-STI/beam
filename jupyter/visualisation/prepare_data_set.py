#!/usr/bin/env python
# coding: utf-8

# # scenario class init block

# In[4]:


import json
import os
import pathlib
import shutil
import random

import pandas as pd
import numpy as np

from datetime import datetime
from pyproj import CRS, Transformer


pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('display.max_colwidth', 1000)


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

        self.network_written = False
        self.dynamic_network_written = False
        
    
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
        if self.network_written:
            self.log("network already written out to file.")
        else:
            network_path = (self.out_path / "network.csv").resolve()
            self.network.to_csv(network_path, index=False)
            self.log(f"network written to '{network_path}'")

            network_settings = { "NetworkWidth":3, "NetworkColor":"0044EE", "FileName":"network.csv" }
            network_settings_path = str((self.out_path / self.network_layer_file).resolve())
            with open(network_settings_path, "w") as file:
                file.write(json.dumps(network_settings))
                
            self.log(f"network settings written to {network_settings_path}")
            
            self.network_written = True
            self.network = pd.DataFrame()

        
    def write_dynamic_network_with_settings(self):
        if self.dynamic_network_written:
            self.log("dynamic network already written out to file.")
        else:
            network_path = (self.out_path / "dynamic_network.csv").resolve()
            self.dynamic_network.to_csv(network_path, index=False)
            self.log(f"dynamic network written to {network_path}")

            network_settings = { "FileName":"dynamic_network.csv" }
            network_settings_path = str((self.out_path / self.dynamic_network_layer_file).resolve())
            with open(network_settings_path, "w") as file:
                file.write(json.dumps(network_settings))
            
            self.log(f"dynamic network settings written to {network_settings_path}")
            
            self.dynamic_network_written = True
            self.dynamic_network = pd.DataFrame()
        
        
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

        
    def write_network(self):
        self.write_network_with_settings()
        self.write_dynamic_network_with_settings()
        
        
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


# # independant functions init block

# In[5]:


### a set of functions to read and process BEAM events

def read_events(path_to_events_file, event_types=None, nrows=None):
    events_dtype = { 
        'riders' : str,
        'driver' : str,
        'vehicle' : str,
        'person' : str,
        'links': str
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


def get_trips(events_df):

    def get_mode_or_action(row):
        event_type = row['type']
        if event_type == 'actend' or event_type == 'actstart':
            return "A:"
        elif event_type == 'PersonEntersVehicle':
            return "V:"  + row['vehicle']
        
        print(f"Unexpected event type: {event_type}")
        return np.nan

    
    def get_sequences_of_vehicles_and_indexes_per_trip(row):
        action_sequence = row['sequence']
        index_sequence = row['index']
        
        result_action_seq = []
        result_index_seq = []
        
        vehicle_used = []
        index_used = []

        for (step, idx) in zip(action_sequence, index_sequence):
            if step.startswith("A:"):
                if len(vehicle_used) > 0:
                    result_action_seq.append(vehicle_used)
                    if index_used[-1] != idx:
                        index_used.append(idx)
                        
                    result_index_seq.append(index_used)

                vehicle_used = []
                index_used = [idx]
                
            if step.startswith("V:"):
                vehicle_used.append(step[2:])
                index_used.append(idx)

        if len(result_action_seq) > 0 and len(result_index_seq) > 0:
            return result_action_seq, result_index_seq
        else:
            return np.nan, np.nan
    

    ## get mode choice and person enters vehicle events
    selected_types = set(['actend', 'actstart', 'PersonEntersVehicle'])
    is_type = events_df['type'].isin(selected_types)
    events_df2 = events_df[is_type].dropna(axis=1, how='all')
    
    if len(events_df2) == 0:
        return pd.DataFrame()
    
    ## addind field 'sequence' with vehicle types and modes for selected events
    events_df2['sequence'] = events_df2.apply(get_mode_or_action, axis=1)
    
    ## group by person 
    persons_df = events_df2.groupby('person')[['index','sequence']].agg(list)
    
    ## transform sequence of modes and vehicles into lists of lists (vehicles, events indexes)
    persons_df[['vehicles_sequence', 'index_sequence']] = persons_df.apply(get_sequences_of_vehicles_and_indexes_per_trip, axis=1, result_type="expand")
    persons_df.dropna(subset=['vehicles_sequence','index_sequence'], how='all', inplace=True)
    
    ## explode DF in order to have one row per trip with pair: mode, used vehicles
    persons_vehicles_sequences = persons_df.explode(['vehicles_sequence', 'index_sequence'])[['vehicles_sequence', 'index_sequence']]
    
    one_trip_per_row_df = persons_vehicles_sequences \
        .reset_index() \
        .reset_index() \
        .rename(columns={'index': 'trip_id', 'index_sequence':'index', "vehicles_sequence": "trip_vehicles"})
    
    return one_trip_per_row_df


def add_vehicle_type_to_all_events_with_vehicles(events_df):
    original_columns = list(events_df.columns) + ['index']
    
    vehicle_not_na = events_df['vehicle'].notna()
    vehicle_type_not_na = events_df['vehicleType'].notna()
    vehicle_to_mode = events_df[vehicle_not_na & vehicle_type_not_na].groupby('vehicle')['vehicleType'].first()
    
    events_df_1 = events_df.drop(columns='vehicleType').reset_index()
    merged_events = events_df_1.merge(vehicle_to_mode, how='outer', on='vehicle')[original_columns].set_index('index')
    return merged_events


def sort_events(events_df):
    ## all events have default order
    events_df['order'] = 7
    
    body_vehicles = set(events_df[events_df['vehicleType'] == 'BODY-TYPE-DEFAULT']['vehicle'].unique())
    
    ## changing order of all events of specified type
    events_df.loc[events_df['type'] == 'actend', 'order'] = 2
    events_df.loc[events_df['type'] == 'PersonEntersVehicle', 'order'] = 3
    events_df.loc[events_df['type'] == 'PersonLeavesVehicle', 'order'] = 4
    events_df.loc[(events_df['type'] == 'PersonLeavesVehicle') & (events_df['vehicle'].isin(body_vehicles)), 'order'] = 8
    events_df.loc[events_df['type'] == 'actstart', 'order'] = 10
    
    ## fixing time of PathTraversal events
    events_df.loc[events_df['type'] == 'PathTraversal', 'time'] = events_df.loc[events_df['type'] == 'PathTraversal', 'departureTime']
    
    ## ordering events by time and then order 
    return events_df.sort_values(['time', 'order'])    


def add_person_to_path_traversal(events_df):
    is_pte_with_riders = (events_df['type'] == 'PathTraversal') & (events_df['riders'].notna())
    pte_df = events_df[is_pte_with_riders].copy()
    rest_df = events_df[~is_pte_with_riders].copy()
    
    pte_df['person'] = pte_df.apply(lambda r: r['riders'].split(":"), axis=1)
    pte_df_one_per_person = pte_df.explode('person')
    
    events_df_merged = pd.concat([pte_df_one_per_person, rest_df])
    
    return events_df_merged


def add_trip_id(events_df):
    if 'index' not in events_df.columns:
        events_df.reset_index(inplace=True)
        
    if 'trip_id' in events_df.columns:
        print("NOT CHANGING ANYTHING, 'trip_id' column already present in events DF!!")
        return events_df
    
    ## getting trips, a df with one trip per row [ trip_id person trip_vehicles index ]
    one_trip_per_row_df = get_trips(events_df)

    ## explode to have one row per event index
    one_row_per_event_df = one_trip_per_row_df.explode('index').drop(columns=['person'])

    ## merge original events into trip list
    events_with_trips = pd.merge(events_df, one_row_per_event_df, left_on='index', right_on='index', how='outer')
    events_with_trips.insert(2, 'trip_id', events_with_trips.pop('trip_id'))
        
    return (sort_events(events_with_trips), one_trip_per_row_df)


def fix_person_enters_leaves_rh_body_events(original_df_trip):
    df_trip = original_df_trip.copy()
    is_rh = df_trip['vehicle'].str.contains('rideHailVehicle')
    is_body = df_trip['vehicle'].str.contains('body')
    is_pte = df_trip['type'] == 'PathTraversal'
    unique_vehicles = df_trip[(is_rh | is_body) & is_pte]['vehicle'].unique()
    
    for vehicle_id in unique_vehicles:
        is_vehicle = df_trip['vehicle'] == vehicle_id
        rh_ptes = df_trip[is_vehicle & is_pte]
        
        min_departure_time = rh_ptes['departureTime'].min()
        max_arrival_time = rh_ptes['arrivalTime'].max()
        
        df_trip.loc[is_vehicle & (df_trip['type'] == 'PersonEntersVehicle'), 'time'] = min_departure_time
        df_trip.loc[is_vehicle & (df_trip['type'] == 'PersonLeavesVehicle'), 'time'] = max_arrival_time
        
    return df_trip


def fix_pte_walk_events(original_df_trip):
    df_trip = original_df_trip.copy()
    
    ptes = df_trip[df_trip['type'] == 'PathTraversal'].copy()
    ptes['is_body'] = ptes.apply(lambda r: 'body' in r['vehicle'], axis=1)
    ptes['is_rh'] = ptes.apply(lambda r: 'rideHailVehicle' in r['vehicle'], axis=1)
    ptes['is_rh_next'] = ptes['is_rh'].shift(-1)
    ptes['departure_next'] = ptes['departureTime'].shift(-1)
    
    is_walk = ptes['is_body'] == True
    rh_next = ptes['is_rh_next'] == True
    time_is_wrong = ptes['departure_next'] < ptes['arrivalTime']
    
    wrong_walk_pte = ptes[is_walk & rh_next & time_is_wrong].copy()
    wrong_walk_pte['time_shift'] = wrong_walk_pte.apply(lambda r: r['departure_next'] - r['arrivalTime'], axis=1)

    df_trip_2 = pd.merge(df_trip, wrong_walk_pte[['index', 'time_shift']], left_on='index', right_on='index', how='outer')
    
    def shift_time(row):
        time_shift = row['time_shift']
        if pd.notna(time_shift):
            for col in ['time', 'departureTime', 'arrivalTime']:
                row[col] += time_shift

        return row
    
    df_trip_2 = df_trip_2.apply(shift_time, axis=1)
    
    columns_to_remove = set(df_trip_2.columns) - set(original_df_trip.columns)
    df_trip_3 = df_trip_2.drop(columns = columns_to_remove)
    
    return df_trip_3
    
    
def fix_act_end(original_df_trip):
    df_trip = original_df_trip.copy()
    
    min_time = df_trip['time'].min()
    is_act_end = df_trip['type'] == 'actend'
    
    df_trip.loc[is_act_end, 'time'] = min_time
    
    return df_trip


def get_trip_df(events_df, selected_trip_id):
    if 'trip_id' not in events_df.columns:
        raise Exception(f"The input DF does not have 'trip_id' column!")
        
    # condition to select events with set trip ID
    trip_condition = events_df['trip_id'] == selected_trip_id
    
    trip_events = events_df[trip_condition]
    used_persons = list(trip_events['person'].unique())
    used_vehicles = set(trip_events['vehicle'].unique())
    
    allowed_time_delta = 100
    time_min = trip_events['time'].min() - allowed_time_delta
    time_max = trip_events['time'].max() + allowed_time_delta
    
    if len(used_persons) > 1:
        raise Exception("Too many persons in one trip: " + ", ".join(selected_persons_list))
    if len(used_persons) < 1:
        raise Exception("There are 0 persons in the selected trip")
    
    selected_person = used_persons[0]
    
    is_pte = events_df['type'] == 'PathTraversal'
    pte_within_time_window = (events_df['departureTime'] > time_min) & (events_df['departureTime'] < time_max)
    
    person_is_rider = events_df['person'] == selected_person
    person_is_driver = events_df['driver'] == selected_person
    person_driver_or_rider = person_is_rider | person_is_driver
    
    # condition to select PathTraversal events
    pte_condition = is_pte & pte_within_time_window & person_driver_or_rider

    is_plv = events_df['type'] == 'PersonLeavesVehicle'
    person_selected = events_df['person'] == selected_person
    within_time_window = (events_df['time'] > time_min) & (events_df['time'] < time_max)
    
    # condition to select PersonLeavesVehicle events
    plv_condition = is_plv & person_selected & within_time_window

    trip_data_frame = events_df[trip_condition | pte_condition | plv_condition]
    
    # fixes for wrong time in events when RH with stops feature enabled
    fixed_trip_df_1 = fix_pte_walk_events(trip_data_frame)
    fixed_trip_df_2 = fix_person_enters_leaves_rh_body_events(fixed_trip_df_1)
    fixed_trip_df_3 = fix_act_end(fixed_trip_df_2)

    return sort_events(fixed_trip_df_3)


#
# HOW TO USE read_events_enhanced_events_trips function
#
## 1. execute code in cell 1 with correct path to events 
## 2. execute code in cell 2
## 3. execute code in cell 3 (optionally change selected_trip_id in cell 3)
#
## results of cell 1 include: whole original events DF, enhanced events DF and all trips DF
## enhanced events has trip_id for some events types, improved order and one PathTraversal event per rider (instead of one PathTraversal event per vehicle)
## %%time - is a magic command which calculates how long the execution of the whole cell took, this command should be the first row in a cell
#
# %%time
# ## CELL 1 - read all events
# path1 = "../beam_root/output/sf-light/multiple_rhm__2023-11-09_19-15-47_aet/ITERS/it.0/0.events.csv.gz"
# events_original, events, all_trips = read_events_enhanced_events_trips(path1)
# print(f"Size of original events DF: {len(events_original)}, enhanced events DF: {len(events)}, all trips DF: {len(all_trips)}")
# display(events_original.head(2))
#
# %%time
# ### CELL 2 - find the required trip to use
# is_vehicle = all_trips['trip_vehicles'].str.contains('some-vehicle-id')
# selected_trips = all_trips[is_vehicle]
# all_trip_ids = list(all_trips['trip_id'].unique())
# selected_trip_ids = list(selected_trips['trip_id'].unique())
# display(f"Trips selected: {len(selected_trip_ids)}")
# display(selected_trips.head(2))
#
# %%time
# ### CELL 3 - show selected trip
# selected_trip_id = random.choice(selected_trips)
# trip_df = get_trip_df(events, selected_trip_id)
# display(f"Trip Id {selected_trip_id}, number of events in it: {len(trip_df)}")
# columns = ['person', 'trip_id', 'type', 'vehicle', 'mode', 'time', 'departureTime', 'arrivalTime', 'length']
# display(trip_df[columns])
#
def read_events_enhanced_events_trips(path_to_events_file):
    ## reading all events without changes
    all_events = read_events(path_to_events_file)
    
    ## adding vehicle type to all events with vehicle, for correct sorting
    ## all_events_with_vehicle_type = add_vehicle_type_to_all_events_with_vehicles(all_events)

    ## cloning PathTraversal events to have one PTE events per rider
    events_with_one_pte_per_rider = add_person_to_path_traversal(all_events)

    ## adding trip id to actend, actstart and PersonEntersVehicle events
    events, all_trips = add_trip_id(events_with_one_pte_per_rider)
    
    ## return all events, enhanced event and all trips
    return (all_events, events, all_trips)



print("initialized")


# # scenarios

# ## an empty scenario with map and without events\trajectories

# In[ ]:


beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "sflight-1k-network-only"
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()
scenario.write_scenario()
scenario.pack_to_archive()


# ## all RH PT events split into 3 groups: without passengers, with 1 passenger, with more passengers

# In[ ]:


beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "sflight-1k-rh_passengers"
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


# ## all RH PT events split into 3 groups: withpassengers, dead heading, repositioning

# In[ ]:


# beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
# output_folder_path = "sflight-1k_rh_deadheading_repositioning"
beam_output = "../downloaded_data/sfbay/sfbay-freight-23Jan24-Base__2024-01-31_18-10-36_gfh"
output_folder_path = "sfbay-freight-23Jan24-Base-rh_passengers"

beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()

all_pte = read_pte_events(beam_output, 0)
# is_rh = all_pte['vehicleType'] == "RH_Car"
is_rh = all_pte['driver'].str.startswith('rideHailAgent')
all_rh = all_pte[is_rh].copy()

print(f" ->> total number of RH rows in DF {len(all_rh)}")
display(all_rh.head(2))


# In[ ]:





# In[ ]:


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


# In[ ]:





# In[ ]:





# ## all bus PT events split based on passengers

# In[ ]:


beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "sflight-1k_bus_passengers"
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


# ## all PT events split into BUS | CAR | WALK

# In[3]:


# beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
# output_folder_path = "sflight-1k_bus_car_walk_all_pte_by_mode"
beam_output = "../downloaded_data/sfbay/sfbay-freight-23Jan24-Base__2024-01-31_18-10-36_gfh"
output_folder_path = "sfbay-freight-23Jan24-Base_bus_car_walk_all_pte_by_mode_025_sample"

beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()
scenario.write_network()


# In[4]:


all_pte = read_pte_events(beam_output, 0)

print(f" ->> total number of rows in DF {len(all_pte)}")
display(all_pte.head(2))

all_pte['mode'].value_counts()


# In[5]:


import math

all_vehicle_ids = all_pte['vehicle'].unique()
random.shuffle(all_vehicle_ids)

number_of_samples = math.floor(len(all_vehicle_ids) * 0.25)
selected_vehicle_ids = set(all_vehicle_ids[:number_of_samples])

print(f"selected:{len(selected_vehicle_ids)}, total:{len(all_vehicle_ids)}, ratio:{len(selected_vehicle_ids) / len(all_vehicle_ids)}, sanity:{(len(selected_vehicle_ids) - number_of_samples) == 0}")


# In[6]:


is_selected = all_pte['vehicle'].isin(selected_vehicle_ids)
sampled_pte = all_pte[is_selected]

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
    elif mode == 'car' or mode == 'car_hov2' or mode == 'car_hov3':
        return "CAR"
    else:
        return "WALK"

def pte_to_progress_bar(pte):
    return "None"


scenario.set_trajectoris(sampled_pte, pte_to_icon_type, pte_to_progress_bar, icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[7]:


get_ipython().system(' ls -lahS | head -5')


# In[ ]:





# In[ ]:





# ## all bus\rh PT events with passengers + all car PT events 

# In[ ]:


beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "sflight-1k_with_passengers_only_bus_car_walk_pte_by_mode"
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


# ## selected actor trip

# In[ ]:


#
# HOW TO USE read_events_enhanced_events_trips function
#
## 0. execute this cell
## 1. execute code in cell 1 with correct path to events 
## 2. execute code in cell 2
## 3. execute code in cell 3 (optionally change selected_trip_id in cell 3)
#
## results of cell 1 include: whole original events DF, enhanced events DF and all trips DF
## enhanced events has trip_id for some events types, improved order and one PathTraversal event per rider (instead of one PathTraversal event per vehicle)
## %%time - is a magic command which calculates how long the execution of the whole cell took, this command should be the first row in a cell

beam_output = "../beam_root/output/sf-light/sf-light-1k-xml__2024-05-06_18-09-08_xjf"
output_folder_path = "sflight-1k_selected_actor_trip_only"
beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()
print('scenario prepared')


# In[ ]:


get_ipython().run_cell_magic('time', '', '## CELL 1 - read all events\npath1 = f"{beam_output}/ITERS/it.0/0.events.csv.gz"\nevents_original, events_enhanced, all_trips = read_events_enhanced_events_trips(path1)\n\n## adding len of the trip\nall_trips[\'trip_len\'] = all_trips.apply(lambda r: len(r[\'index\']), axis=1)\nall_trips[\'veh_number\'] = all_trips.apply(lambda r: len(r[\'trip_vehicles\']), axis=1)\n    \n\nprint(f"Size of original events DF: {len(events_original)}, enhanced events DF: {len(events_enhanced)}, all trips DF: {len(all_trips)}")\n# display(events_original.head(2))\n# display(events_enhanced.head(2))\ndisplay(all_trips.head(2))\n')


# In[ ]:


get_ipython().run_cell_magic('time', '', '### CELL 2 - find the required trip to use\nmore_vehicles = max(all_trips[\'veh_number\'].unique())\nselected_trips = all_trips[all_trips[\'veh_number\'] == more_vehicles - 1]\nprint(f"there are {len(selected_trips)} selected trips")\n\ndisplay(selected_trips.head(2))\n')


# In[ ]:


get_ipython().run_cell_magic('time', '', '### CELL 3 - show selected trip_id\nselected_trip_id = random.choice(selected_trips[\'trip_id\'].unique())\ndisplay(f"Trip Id {selected_trip_id}")\ntrip_df = get_trip_df(events_enhanced, selected_trip_id)\ndisplay(f"Number of events in it: {len(trip_df)}")\ncolumns = [\'person\', \'trip_id\', \'type\', \'vehicle\', \'mode\', \'time\', \'departureTime\', \'arrivalTime\', \'length\', \'links\', \'linkTravelTime\']\ndisplay(trip_df[columns])\n')


# In[ ]:


selected_person = "032802-2015000455334-0-7952563"

is_pte = events_original['type'] == 'PathTraversal'
with_links = events_original['links'].notna()
all_pte = events_original[is_pte & with_links].dropna(axis=1, how='all').copy()

def is_selected(row):
    if row['driver'] == selected_person:
        return True
    riders = row['riders']
    if riders and selected_person in str(riders):
        return True
    return False

all_pte['selected'] = all_pte.apply(is_selected, axis=1)

time_min = all_pte[all_pte['selected'] == True]['time'].min()
time_max = all_pte[all_pte['selected'] == True]['time'].max()

time_more_than_min = all_pte['time'] > (time_min - 10 * 60.0)
time_less_than_max = all_pte['time'] < (time_max + 10 * 60.0)

all_pte = all_pte[time_more_than_min & time_less_than_max]

print(f"there are {len(all_pte)} events in dataframe")
display(all_pte.head(2))
display(all_pte['selected'].value_counts())


# In[ ]:


icons = [
    {
        "Type":"WALK",
        "BackgroundColor":"c4c4c4",
        "Label":"walk of selected agent",
        "Icon":"Triangle"
    },
    {
        "Type":"RH",
        "BackgroundColor":"bf2e2e",
        "Label":"RH of selected agent",
        "Icon":"Triangle"
    },
    {
        "Type":"REST",
        "BackgroundColor":"919191",
        "Label":"the rest of PT event",
        "Icon":"Triangle"
    }
]

def pte_to_icon_type(path_traversal_event):
    if path_traversal_event['selected'] == False:
        return "REST"
    
    mode = path_traversal_event['mode']
    if mode == 'walk':
        return "WALK"
    elif mode == 'car':
        return "RH"
    else:
        return "REST"

def pte_to_progress_bar(pte):
    return "None"

selected_pte_only = all_pte[all_pte['selected'] == True].copy()

scenario.set_trajectoris(selected_pte_only, pte_to_icon_type, pte_to_progress_bar, icons)
scenario.write_scenario()
scenario.pack_to_archive()


# In[ ]:


selected_pte_only = all_pte[all_pte['selected'] == True].copy()
selected_pte_only


# In[ ]:


events_original[events_original['driver'] == "032802-2015000455334-0-7952563"].dropna(axis=1, how='all')


# In[ ]:





# ## subway as events

# In[6]:


beam_output = "../beam_root/output/sf-light/sf-light-1k-walk-transit__2024-07-01_19-15-50_dtv"
output_folder_path = "sflight-1k_subway_plus_walk"
# beam_output = "../downloaded_data/sfbay/sfbay-freight-23Jan24-Base__2024-01-31_18-10-36_gfh"
# output_folder_path = "sfbay-freight-23Jan24-Base_bus_car_walk_all_pte_by_mode_025_sample"

beam_crs = 26910

scenario = Scenario(beam_output, beam_crs, output_folder_path)
scenario.read_beam_output()
scenario.write_network()


# In[8]:


events_path = get_events_file_path(beam_output, 0)
all_events = read_events(events_path)
all_events.head()


# In[ ]:





# In[ ]:





# In[ ]:




