from utils import GEMINI_XFC_SCRATCH_FOLDER
import numpy as np
import pyproj
import os
from sklearn.neighbors import BallTree
import pathlib
## this script reads all the charging station locations and adds charging load
## points to the dss files

def get_station_locations(taz_csv_file="cs_data/taz-centers.csv"):
    # read in taz locations
    taz_dict = {}
    taz_ids = []
    taz_coord_frame = pyproj.Proj(init='epsg:26910')
    lon_lat_frame = pyproj.Proj(init='epsg:5070')
    with open(taz_csv_file, 'r') as taz_file:
        taz_lines = taz_file.readlines()
        taz_lines = taz_lines[1:] # the first line is headings
        for line in taz_lines:
            if len(line)>4:
                taz_info = line.split(',')
                taz_id = taz_info[0]
                taz_ids.append(taz_id)
                taz_data = {}
                x_coord = float(taz_info[1])
                y_coord = float(taz_info[2])
                coords = pyproj.transform(taz_coord_frame, lon_lat_frame, x_coord, y_coord)
                # transform to longitude latitude
                taz_data['coords'] = coords #np.array(coords).reshape(1,-1)
                taz_data['stations'] = [] # this list will be filled later
                taz_data['n_plugs'] = []
                taz_data['parking_type'] = []
                taz_data['charger_type'] = []
                taz_dict[taz_id] = taz_data
    n_taz = len(taz_ids)
    return taz_ids, taz_dict, n_taz

def get_station_info(taz_dict, station_file_path="cs_data/gemini_depot_parking_power_150kw_250kw.csv"):
    # read in charging station info
    with open(station_file_path, 'r') as station_file:
        station_lines = station_file.readlines()
        station_lines = station_lines[1:] # the first line is headings
        # if there is content in the line, assign the station to a taz
        for line in station_lines:
            if len(line)>4:
                station_info = line.split(',')
                taz_id = station_info[0]
                numStalls = int(station_info[4])
                parking_type = station_info[1]
                charger_type = station_info[3]
                taz_dict[taz_id]['n_plugs'].append(numStalls)
                taz_dict[taz_id]['parking_type'].append(parking_type)
                taz_dict[taz_id]['charger_type'].append(charger_type)
    return taz_dict

def get_dist_bus_info():
    # read in bus locations
    # for san francisco you will need to convert from EPSG:32610 to EPSG:4326
    bus_coord_frame = pyproj.Proj(init='epsg:32610')
    lon_lat_coord_frame = pyproj.Proj(init='epsg:5070')
    # find all the regions you have distribution data for
    potential_regions = (GEMINI_XFC_SCRATCH_FOLDER / 'simulation').glob('*')
    regions = []
    for folder in potential_regions:
        #folder_path = (GEMINI_XFC_SCRATCH_FOLDER / 'simulation' / folder)
        folder_name = str(folder).split('/')[-1]
        if os.path.isdir(folder) and len(folder_name)<=4:
            regions.append(folder)
    # go through all the regions and locate all buses at 1247 kV or lower
    bus_file_dict = {}
    busnames = []
    buscoords = []
    for region in regions:
        # find all subregions
        potential_sub_regions = region.glob('*')
        region_name = str(region).split('/')[-1]
        sub_regions = []
        for folder in potential_sub_regions:
            sub_region_name = str(folder).split('/')[-1]
            if sub_region_name.startswith(region_name) and (folder / "DSSfiles/Buscoords.dss").exists():
                sub_regions.append(folder)
        # get all Buscoords
        for sub_region in sub_regions:
            bus_file = (sub_region / "DSSfiles/Buscoords.dss")
            with open(bus_file, 'r') as bc_file:
                bc_lines = bc_file.readlines()
            for line in bc_lines:
                if len(line)>4:
                    bus_entry = line.split(' ')
                    if bus_entry[1][0].isdigit():
                        busnames.append(bus_entry[0])
                        bus_x = float(bus_entry[1])
                        bus_y = float(bus_entry[2])
                        bus_coords = pyproj.transform(bus_coord_frame, lon_lat_coord_frame, bus_x, bus_y)
                        #buscoords_long.append(bus_coords[0])
                        #buscoords_lat.append(bus_coords[1])
                        buscoords.append(bus_coords) 
                        bus_file_dict[bus_entry[0]]= bus_file
    buscoords = np.array(buscoords)
    return bus_file_dict, buscoords, busnames

def assign_stations_to_buses_nn(station_dict, taz_ids, buscoords, busnames, n_taz, n_buses):
    # use a nearest neighbor approach to pair buses and stations
    station_bus_pairs = []
    all_taz_coords = []
    # get list of coordinate pairs for all taz
    for taz_id in taz_ids:
        taz_coords = station_dict[taz_id]['coords']
        all_taz_coords.append(taz_coords)

    tree = BallTree(buscoords, leaf_size=15)
    bus_taz_distances, indices = tree.query(all_taz_coords, k=1) # make list of distances with groups of k nearest neighbors
    
    # make bus_station_pairs include extra info later
    station_bus_pair_strings = []
    i = 0
    for taz_id in taz_ids:
        # each taz location has many charging station options
        for j_at_taz in range(len(station_dict[taz_id]['n_plugs'])):
            parking_type = station_dict[taz_id]['parking_type'][j_at_taz]
            charger_type = station_dict[taz_id]['charger_type'][j_at_taz]
            n_plugs = station_dict[taz_id]['n_plugs'][j_at_taz]
            station_id = f"cs_{taz_id}_{parking_type}_{charger_type}_{n_plugs}"
            station_bus_pairs.append((station_id, busnames[indices[i][0]]))
            station_bus_pair_strings.append(f'{station_id}, {busnames[indices[i][0]]} \n')
        i +=1
    # record charging station bus pairs in a csv file
    with open('station_bus_pairs.csv', 'w') as sbp_file:
        sbp_file.writelines(station_bus_pair_strings)

    return station_bus_pairs, bus_taz_distances

#def assign_stations_to_buses_greedy(taz_dict, taz_ids, buscoords_long, buscoords_lat, busnames, n_taz, n_buses):
#    bus_station_pairs = []
#    # go down list of charging stations and assign to closest bus
#    for taz_id in taz_ids:
#        connection_distance = []
#        taz_long = taz_dict[taz_id]['long']
#        taz_lat = taz_dict[taz_id]['lat']
#        # make list of distances
#        for i_bus in range(n_buses):
#            dist = np.sqrt((taz_long-buscoords_long[i_bus])**2 + (taz_lat-buscoords_lat[i_bus])**2)
#            connection_distance.append(dist)
#        i_bus_min = connection_distance.index(min(connection_distance))
#        bus_station_pairs.append((taz_id, busnames[i_bus_min]))
#    return bus_station_pairs
#
#def assign_stations_to_buses(taz_dict, taz_ids, buscoords_long, buscoords_lat, busnames, n_taz, n_buses):
#    # assign charging station connections to buses with closest location
#    # and appropriate voltage levels
#    # optimize for minimum total distance when connecting charging stations to buses
#    # using mixed integer linear solver pulp
#    all_possible_distances = []
#    connection_names = []
#    connection_binary = [] # mixed integer variable (1 or 0) denoting if connected
#    index_by_taz = [[]]*n_taz
#    index_by_bus = [[]]*n_buses
#    i_lpvar = 0 # index of the optimization variable
#    i_taz = 0 # index of the taz id
#    for taz_id in taz_ids:
#        for i_bus in range(n_buses):
#            distance = np.sqrt((taz_dict[taz_id]['long']-buscoords_long[i_bus])**2 + (taz_dict[taz_id]['lat']-buscoords_lat[i_bus])**2)
#            all_possible_distances.append(distance)
#            connection_name = f"taz_{taz_id}_bus_{busnames[i_bus]}"
#            connection_names.append(connection_name)
#            index_by_taz[i_taz].append(i_lpvar)
#            index_by_bus[i_bus].append(i_lpvar)
#            connection_binary.append(pulp.LpVariable(connection_name, lowBound=0, upBound=1, cat='Integer'))
#            i_lpvar+=1
#        i_taz+=1
#    print('created connection variables for optimization')
#    total_dist = pulp.LpVariable("total_distance", 0, 10e7) # this max value is way high
#    prob = pulp.LpProblem("assign_stations", pulp.LpMinimize)
#
#    # distance objective function
#    prob += (pulp.lpSum([all_possible_distances[i]*connection_binary[i] for i in range(len(connection_binary))]))
#
#    # every station is connected
#    for i_taz in range(n_taz):
#        prob += (pulp.lpSum([connection_binary[i_connection] for i_connection in index_by_taz[i_taz]]) == 1)
#
#    # solve problem and parse
#    print('optimization problem setup, solving now')
#    prob.solve()
#    print('probem solved, parsing solution')
#    # parse solution
#    station_bus_pairs = []
#    for connection in prob.variables():
#        if connection.varValue > 0:
#            station_to_bus = connection.name.split("_")
#            station_id = station_to_bus[1]
#            bus_id = station_to_bus[3]
#            station_bus_pairs.append((station_id, bus_id))
#    return station_bus_pairs

def add_csloads_to_dssfiles(station_bus_pairs, bus_dict, bus_taz_dists):
    # edit load files to include new charging station load
    i = 0
    for station_id, bus_id in station_bus_pairs:
        bus_taz_dist = bus_taz_dists[i]
        bus_coords_file = bus_dict[bus_id]
        new_bus_1 = f"{bus_id}_cs"
        cs_load_bus = f"bus_{station_id}"
        # bus_load_file = str(bus_coords_file).replace("Buscoords", "Loads")
        charging_station_file = str(bus_coords_file).replace("Buscoords", "EV_ChargingStations")
        bus_master_file = str(bus_coords_file).replace("Master.dss")
        with open(bus_master_file, 'r') as master_file:
            master_lines = master_file.readlines()
        redirect_line = 'Redirect EV_ChargingStations.dss'
        master_lines.append(redirect_line)
        with open(bus_master_file, 'w') as master_file:
            master_file.writelines(master_lines)
        ## if you don't have a load file for that bus, just add it to the same file as the coordinates
        #if not pathlib.Path(bus_load_file).exists():
        #    bus_load_file = bus_coords_file
        #transformer_file = str(bus_coords_file).replace("Buscoords", "Transformers")
        #bus_line_file = str(bus_coords_file).repolace("Buscoords", "Lines")
        ## if you don't have a line file for that bus just add it to the same file as the coordinates
        #if not pathlib.Path(bus_line_file).exists():
        #    bus_line_file = bus_coords_file
        # add new line to list of lines
        #with open(bus_line_file, 'r') as line_file:
        #    line_lines = line_file.readlines()
        cs_file_lines = []
        line_string = f'New Line.l(r:{bus_id}-{new_bus_1}) Units=km Length={bus_taz_dist} bus1={bus_id}.1.2.3 bus2={new_bus_1} switch=n enabled=y Linecode=3P_OH_AL_ASCR_4/0_Penguin_12_47_0_3_3_3 \n'
        #line_lines.append(line_string)
        cs_file_lines.append(line_string)
        # need to add new bus corresponding to new load 
        # all devices define buses, buscoords are just for plotting

        # if you don'e have a transformer file for that bus, just add it to the same file as the coordinates
        #if not pathlib.Path(transformer_file).exists():
        #    transformer_file = bus_coords_file
        ## add transformer to bus
        #with open(transformer_file, 'r') as tran_file:
        #    tran_lines = tran_file.readlines()
        transformer_line = f"New Transformer.ts(r:{new_bus_1}-{cs_load_bus}) phases=3 windings=2 %loadloss=1.6 wdg=1 conn=wye Kv=12.47 kva=300.0 EmergHKVA=450.0 %R=0.91198 bus={new_bus_1} wdg=2 conn=wye Kv=0.48 kva=300.0 EmergHKVA=450.0 %R=0.91198 bus={cs_load_bus} XHL=3.39 \n"
        #tran_lines.append(transformer_line)
        cs_file_lines.append(transformer_line)
        #with open(transformer_file, 'w') as tran_file:
        #    tran_file.writelines(tran_lines)
 
        # add load to low voltage side of bus
        #with open(bus_load_file, 'r') as load_file:
        #    load_lines = load_file.readlines()
        # models are defaults in opendss: model 1 is default constant power P,Q
        station_load_line = f"New Load.cs_{station_id} conn=wye bus1={cs_load_bus} kV=0.48 Vminpu=0.8 Vmaxpu=1.2 model=1 kW=1000 kvar=200 Phases=3 \n"#{station['rating']} kvar={station['rating']*0.2} Phases=3 /n"
        #load_lines.append(station_load_line)
        cs_file_lines.append(transformer_line)
        #with open(bus_load_file, 'w') as load_file:
        #    load_file.writelines(load_lines)
        with open(charging_station_file, 'w') as cs_file:
            cs_file.writelines(cs_file_lines)

        i=i+1

def add_load_subscriptions(station_bus_pairs, station_dict, subs_active=True):
    # add subscription topics to each distribution federate corresponding to the charging stations on that feeder
    for station, buscoords_file in station_bus_pairs:
        taz = station_dict[station]['taz']
        parking_type = station_dict[station]['parking_type']
        charger_type = station_dict[station]['charger_type']
        n_plugs = station_dict[station]['n_plugs']
        busname = buscoords_file.split('/DSSfiles')[0].split['/'][-1]
        bus_subs_toml = bus.replace('DSSfiles/Buscoords.dss', 'Scenarios/{busname}/ExportLists/Subscriptions.toml')
        # open the toml for that bus federate's subscriptions
        with open(bus_subs_toml, 'r') as subs_file:
            subs_lines = subs_file.readlines()
        # add lines for subscription of real load
        bus_real_subs = f'["Load.cs{station_id}"] \n Property = "kW" \n "Subscription ID" = "Load.cs_{taz}_{parking_type}_{charger_type}_{n_plugs}" \n Unit = "kW" \n Subscribe = {subs_active} \n "Data Type" = vector \n Multiplier = 1 \n index = 0'
        subs_lines.append(bus_real_subs)
        # add lines for subscription of reactive load
        bus_react_subs = f'["Load.cs{station_id}"] \n Property = "kva" \n "Subscription ID" = "Load.cs_{taz}_{parking_type}_{charger_type}_{n_plugs}" \n Unit = "kva" \n Subscribe = {subs_active} \n "Data Type" = vector \n Multiplier = 1 \n index = 1'
        subs_lines.append(bus_react_subs)
        # re-write the toml
        with open(bus_subs_toml, 'w') as subs_file:
            subs_file.writelines(subs_lines)

def create_load_points(subs_active):
    taz_ids, taz_dict, n_taz = get_station_locations()
    taz_dict = get_station_info(taz_dict)
    print('charging station information loaded')
    bus_dict, buscoords, busnames = get_dist_bus_info()
    print('available buses found')
    station_bus_pairs, bus_taz_dists = assign_stations_to_buses_nn(taz_dict, taz_ids, buscoords, busnames, n_taz, len(buscoords))
    print('stations paired with buses')
    add_csloads_to_dssfiles(station_bus_pairs, bus_dict, bus_taz_dists)
    print('charging station loads added to PyDSS models')
    #add_load_subscriptions(station_bus_pairs, taz_dict, subs_active=subs_active)
    #print(f'federates subscriptions for station loads added to distribution Subscription.tomls with "Subscribe" set to {subs_active}')

if __name__ == "__main__":
    subs_active = False
    create_load_points(subs_active)

