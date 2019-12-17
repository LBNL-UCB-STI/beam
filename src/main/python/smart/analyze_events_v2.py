import json
import plotly.graph_objects as go
import pandas as pd
import os
from jsonmerge import merge


def get_pooling_metrics(_data):
    count_of_multi_passenger_pool_trips = 0
    count_of_one_passenger_pool_trips = 0
    count_of_solo_trips = 0
    count_of_unmatched_pool_requests = 0
    count_of_unmatched_solo_requests = 0
    sum_deadheading_distance_traveled = 0.0
    sum_ride_hail_distance_traveled = 0.0
    mode_choice_attempt = {}
    person_has_shared_a_trip = {}
    passengers_per_veh = {}
    person_in_veh = {}

    ct_nb_requests = {}
    chained_trips_requests = 0
    chained_trips_count = 0
    for row in _data.itertuples():
        person = row.person
        vehicle = row.vehicle
        mode = row.mode
        event = row.type
        passengers = row.numPassengers
        distance = row.length
        if event == "ModeChoice":
            if str(person).startswith("rideHailAgent"):
                print("ride hail driver with mode choice, does it ever occur !?")
            elif mode.startswith("ride_hail"):
                mode_choice_attempt[person] = mode
            elif person in mode_choice_attempt and not mode_choice_attempt[person].endswith("unmatched"):
                mode_choice_attempt[person] = mode_choice_attempt[person] + "_unmatched"
        elif event == "PersonEntersVehicle":
            if person not in mode_choice_attempt:
                continue
            chosen_mode = mode_choice_attempt[person]
            if chosen_mode.endswith("unmatched"):
                if chosen_mode.startswith("ride_hail_pooled"):
                    count_of_unmatched_pool_requests += 1
                else:
                    count_of_unmatched_solo_requests += 1
                del mode_choice_attempt[person]
            elif not vehicle.startswith("rideHailVehicle"):
                i = 0
                # agent started walking towards ride hail vehicle
            elif chosen_mode == "ride_hail_pooled":
                person_in_veh[person] = vehicle
                prev_pool = passengers_per_veh[vehicle] if vehicle in passengers_per_veh else 0
                passengers_per_veh[vehicle] = prev_pool + 1
                for p in {k: v for k, v in person_in_veh.items() if v == vehicle}:
                    if p not in person_has_shared_a_trip or not person_has_shared_a_trip[p]:
                        person_has_shared_a_trip[p] = passengers_per_veh[vehicle] > 1
                # chained trips metrics
                if prev_pool == 0:
                    ct_nb_requests[vehicle] = 0
                ct_nb_requests[vehicle] += 1
            else:
                count_of_solo_trips += 1
        elif event == "PersonLeavesVehicle":
            if person not in mode_choice_attempt:
                continue
            if not vehicle.startswith("rideHailVehicle"):
                i = 0
                # agent ended walking towards the ride hail vehicle
            elif mode_choice_attempt[person] == "ride_hail_pooled":
                if passengers_per_veh[vehicle] > 1:
                    person_has_shared_a_trip[person] = True
                if person_has_shared_a_trip[person] is True:
                    count_of_multi_passenger_pool_trips += 1
                else:
                    count_of_one_passenger_pool_trips += 1
                del person_has_shared_a_trip[person]
                del person_in_veh[person]
                passengers_per_veh[vehicle] -= 1
                # chained trips metrics
                if passengers_per_veh[vehicle] == 0:
                    chained_trips_requests = (chained_trips_requests * chained_trips_count + ct_nb_requests[vehicle])/(chained_trips_count+1)
                    chained_trips_count += 1
            del mode_choice_attempt[person]
        elif event == "PathTraversal":
            if not vehicle.startswith("rideHailVehicle"):
                continue
            if int(passengers) == 0:
                sum_deadheading_distance_traveled += float(distance)
            sum_ride_hail_distance_traveled += float(distance)
    del _data
    tot_pool_trips = count_of_multi_passenger_pool_trips + count_of_one_passenger_pool_trips + \
                     count_of_unmatched_pool_requests
    tot_solo_trips = count_of_solo_trips + count_of_unmatched_solo_requests
    tot_rh_trips = tot_pool_trips + tot_solo_trips
    tot_rh_unmatched = count_of_unmatched_pool_requests + count_of_unmatched_solo_requests
    multi_passengers_trips_per_pool_trips = 0 if tot_pool_trips == 0 \
        else count_of_multi_passenger_pool_trips / tot_pool_trips
    multi_passengers_trips_per_ride_hail_trips = 0 if tot_rh_trips == 0 \
        else count_of_multi_passenger_pool_trips / tot_rh_trips
    unmatched_per_ride_hail_requests = 0 if tot_rh_trips == 0 \
        else tot_rh_unmatched / tot_rh_trips
    deadheading_per_ride_hail_trips = 0 if sum_ride_hail_distance_traveled == 0 \
        else sum_deadheading_distance_traveled / sum_ride_hail_distance_traveled
    return {
        "ride_hail_requests": tot_rh_trips,
        "ride_hail_solo_requests": count_of_solo_trips + count_of_unmatched_solo_requests,
        "ride_hail_pool_requests": tot_pool_trips + count_of_unmatched_pool_requests,
        "multi_passenger_pool_trips": count_of_multi_passenger_pool_trips,
        "one_passenger_pool_trips": count_of_one_passenger_pool_trips,
        "solo_trips": count_of_solo_trips,
        "unmatched_pool_requests": count_of_unmatched_pool_requests,
        "unmatched_solo_requests": count_of_unmatched_solo_requests,
        "deadheading_distance_traveled": sum_deadheading_distance_traveled,
        "ride_hail_distance_traveled": sum_ride_hail_distance_traveled,
        "multi_passengers_trips_per_pool_trips": multi_passengers_trips_per_pool_trips,
        "multi_passengers_trips_per_ride_hail_trips": multi_passengers_trips_per_ride_hail_trips,
        "unmatched_per_ride_hail_requests": unmatched_per_ride_hail_requests,
        "deadheading_per_ride_hail_trips": deadheading_per_ride_hail_trips,
        "chained_trips_requests": chained_trips_requests,
        "chained_trips_count": chained_trips_count
    }


def get_all_metrics(filename, __local_file_path):
    metrics_json = {}
    pool_metrics_file_path = "{}.pooling-metrics.json".format(__local_file_path)
    if os.path.exists(pool_metrics_file_path):
        with open(pool_metrics_file_path) as f:
            metrics_json = json.load(f)

    compression = None
    if filename.endswith(".gz"):
        compression = 'gzip'
    data = pd.read_csv(filename, sep=",", index_col=None, header=0, compression=compression)
    modeChoice = data.loc[data['type'] == 'ModeChoice'].dropna(how='all', axis=1)
    pathTraversal = data.loc[data['type'] == 'PathTraversal'].dropna(how='all', axis=1)
    print("get_all_metrics ...")
    if len(metrics_json) == 0:
        ride_hail_mc = modeChoice[modeChoice['mode'].str.startswith('ride_hail')]
        ride_hail_mc_users = set(ride_hail_mc['person'])
        data2 = data[(data['type'].isin(['PathTraversal']) & data['vehicle'].str.startswith('rideHailVehicle')) |
                     (data['type'].isin(['ModeChoice', 'PersonEntersVehicle', 'PersonLeavesVehicle']) &
                      data['person'].isin(ride_hail_mc_users))]
        del data
        metrics_json = get_pooling_metrics(data2)
        with open(pool_metrics_file_path, 'w') as outfile:
            json.dump(metrics_json, outfile)
            pooling_sankey_path = __local_file_path.rsplit("/", 1)[0] + "/sankey/" + __local_file_path.rsplit("/", 1)[1]
        #generate_sankey_for_pooling(metrics_json, pooling_sankey_path)
    else:
        del data

    pathTraversal['miles'] = pathTraversal['length'] / 1609.34
    pathTraversal['gallons'] = (pathTraversal['primaryFuel'] + pathTraversal['secondaryFuel']) * 8.3141841e-9
    pathTraversal['mpg'] = pathTraversal['miles'] / pathTraversal['gallons']
    pathTraversal['startingPrimaryFuelLevel'] = pathTraversal['primaryFuelLevel'] + pathTraversal['primaryFuel']
    pathTraversal['mode_extended'] = pathTraversal['mode']
    pathTraversal['isRH'] = pathTraversal['vehicle'].str.contains('rideHail')
    pathTraversal['isCAV'] = pathTraversal['vehicleType'].str.contains('L5')
    pathTraversal.loc[pathTraversal['isRH'], 'mode_extended'] += '_RH'
    pathTraversal.loc[pathTraversal['isCAV'], 'mode_extended'] += '_CAV'
    pathTraversal['trueOccupancy'] = pathTraversal['numPassengers']
    pathTraversal.loc[pathTraversal['mode_extended'] == 'car', 'trueOccupancy'] += 1
    pathTraversal.loc[pathTraversal['mode_extended'] == 'walk', 'trueOccupancy'] = 1
    pathTraversal.loc[pathTraversal['mode_extended'] == 'bike', 'trueOccupancy'] = 1
    pathTraversal['vehicleMiles'] = pathTraversal['length']/1609.34
    pathTraversal['passengerMiles'] = (pathTraversal['length'] * pathTraversal['trueOccupancy'])/1609.34
    pathTraversal['vehicleHours'] = (pathTraversal['arrivalTime'] - pathTraversal['departureTime'])/3600
    pathTraversal['passengerHours'] = pathTraversal['vehicleHours'] * pathTraversal['trueOccupancy']
      
    pathTraversal = pathTraversal.loc[~((pathTraversal['mode']=='walk') & (pathTraversal['vehicleHours']>2)),:]
    
    lightDutyVehiclePathTraversals=pathTraversal.loc[(pathTraversal['vehicleType'].str.contains("BUS")==False) &
                  (pathTraversal['vehicleType'].str.contains("BIKE")==False) &
                  (pathTraversal['vehicleType'].str.contains("BODY")==False) &
                  (pathTraversal['vehicleType'].str.contains("CABLE")==False) &
                  (pathTraversal['vehicleType'].str.contains("FERRY")==False) &
                  (pathTraversal['vehicleType'].str.contains("SUBWAY")==False) &
                  (pathTraversal['vehicleType'].str.contains("TRAM")==False) &
                  (pathTraversal['vehicleType'].str.contains("TRAIN")==False),:]
    
    metrics_json['total_VHT_LightDutyVehicles'] = lightDutyVehiclePathTraversals['vehicleHours'].sum()
    

    modeChoiceTotals = modeChoice.groupby('mode').agg({'person': 'count', 'length': 'sum'})
    for mode in modeChoiceTotals.index:
        metrics_json[mode+'_counts'] = int(modeChoiceTotals.loc[mode,'person'])

    pathTraversalModes = pathTraversal.groupby('mode_extended').agg({'vehicleMiles': 'sum', 'primaryFuel': 'sum', 'secondaryFuel': 'sum', 'passengerMiles': 'sum','vehicleHours': 'sum', 'passengerHours':'sum'})
    for mode in pathTraversalModes.index:
        metrics_json['VMT_' + mode] = float(pathTraversalModes.loc[mode, 'vehicleMiles'])
        metrics_json['PMT_' + mode] = float(pathTraversalModes.loc[mode, 'passengerMiles'])
        metrics_json['VHT_' + mode] = float(pathTraversalModes.loc[mode, 'vehicleHours'])
        metrics_json['PHT_' + mode] = float(pathTraversalModes.loc[mode, 'passengerHours'])
        metrics_json['Energy_' + mode] = float(pathTraversalModes.loc[mode, 'primaryFuel'] + pathTraversalModes.loc[mode, 'secondaryFuel'])

    for mode in pathTraversalModes.index:
        metrics_json['VMT_' + mode + "_empty"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 0), 'vehicleMiles'].sum())
        metrics_json['VMT_' + mode + "_shared"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] > 1), 'vehicleMiles'].sum())
        metrics_json['VMT_' + mode + "_shared_2p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 2), 'vehicleMiles'].sum())
        metrics_json['VMT_' + mode + "_shared_3p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 3), 'vehicleMiles'].sum())
        metrics_json['VMT_' + mode + "_shared_4p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] >= 4), 'vehicleMiles'].sum())
        metrics_json['PMT_' + mode + "_empty"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 0), 'passengerMiles'].sum())
        metrics_json['PMT_' + mode + "_shared"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] > 1), 'passengerMiles'].sum())
        metrics_json['PMT_' + mode + "_shared_2p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 2), 'passengerMiles'].sum())
        metrics_json['PMT_' + mode + "_shared_3p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 3), 'passengerMiles'].sum())
        metrics_json['PMT_' + mode + "_shared_4p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] >= 4), 'passengerMiles'].sum())
        metrics_json['VHT_' + mode + "_empty"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 0), 'vehicleHours'].sum())
        metrics_json['VHT_' + mode + "_shared"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] > 1), 'vehicleHours'].sum())
        metrics_json['VHT_' + mode + "_shared_2p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 2), 'vehicleHours'].sum())
        metrics_json['VHT_' + mode + "_shared_3p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] == 3), 'vehicleHours'].sum())
        metrics_json['VHT_' + mode + "_shared_4p"] = float(pathTraversal.loc[(pathTraversal['mode_extended'] == mode) & (pathTraversal['trueOccupancy'] >= 4), 'vehicleHours'].sum())

    metrics_json['VMT_L1'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L1'), 'vehicleMiles'].sum())
    metrics_json['VMT_L3'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L3'), 'vehicleMiles'].sum())
    metrics_json['VMT_L5'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L5'), 'vehicleMiles'].sum())

    expansion_factor=(7.75/0.315) * 27.0 / 21.3
    
    
    
    transitPathTraversals=pathTraversal.loc[(pathTraversal['vehicleType'].str.contains("BUS")==True) |
                  (pathTraversal['vehicleType'].str.contains("BIKE")==True) |
                  (pathTraversal['vehicleType'].str.contains("BODY")==True) |
                  (pathTraversal['vehicleType'].str.contains("CABLE")==True) |
                  (pathTraversal['vehicleType'].str.contains("FERRY")==True) |
                  (pathTraversal['vehicleType'].str.contains("SUBWAY")==True) |
                  (pathTraversal['vehicleType'].str.contains("TRAM")==True) |
                  (pathTraversal['vehicleType'].str.contains("TRAIN")==True),:]
    
    
    transit_primaryFuelTypes = transitPathTraversals.groupby('primaryFuelType').agg({'primaryFuel': 'sum'})
    transit_secondaryFuelTypes = transitPathTraversals.groupby('secondaryFuelType').agg({'secondaryFuel': 'sum'})
    
    ldv_primaryFuelTypes = lightDutyVehiclePathTraversals.groupby('primaryFuelType').agg({'primaryFuel': 'sum'})
    ldv_secondaryFuelTypes = lightDutyVehiclePathTraversals.groupby('secondaryFuelType').agg({'secondaryFuel': 'sum'})
    
    primaryFuelTypes = pathTraversal.groupby('primaryFuelType').agg({'primaryFuel': 'sum'})
    secondaryFuelTypes = pathTraversal.groupby('secondaryFuelType').agg({'secondaryFuel': 'sum'})
    
    for fueltype in primaryFuelTypes.index:
        metrics_json['totalEnergy_' + fueltype] = 0
    for fuelType in secondaryFuelTypes.index:
        if 'None' not in fuelType:
            metrics_json['totalEnergy_' + fuelType] = 0

    for fueltype in transit_primaryFuelTypes.index:
        metrics_json['totalEnergy_' + fueltype] += float(transit_primaryFuelTypes.loc[fueltype, 'primaryFuel']) /expansion_factor 
    for fuelType in transit_secondaryFuelTypes.index:
        if 'None' not in fuelType:
            metrics_json['totalEnergy_' + fuelType] += float(transit_secondaryFuelTypes.loc[fueltype, 'secondaryFuel'])/expansion_factor

    for fueltype in ldv_primaryFuelTypes.index:
        metrics_json['totalEnergy_' + fueltype] += float(ldv_primaryFuelTypes.loc[fueltype, 'primaryFuel'])
    for fuelType in ldv_secondaryFuelTypes.index:
        if 'None' not in fuelType:
            metrics_json['totalEnergy_' + fuelType] += float(ldv_secondaryFuelTypes.loc[fueltype, 'secondaryFuel'])
            
            
    print("get_all_metrics done")
    return metrics_json


def generate_sankey_for_pooling(_df, _local_filename_itr, _unit=1000.0):
    pool_tot_share = _df["multi_passengers_trips_per_ride_hail_trips"]
    pool_share = _df["multi_passengers_trips_per_pool_trips"]
    solo_share = (_df["solo_trips"]+_df["one_passenger_pool_trips"])/_df["ride_hail_requests"]
    unmatched_share = (_df["unmatched_pool_requests"]+_df["unmatched_solo_requests"])/_df["ride_hail_requests"]
    labels = ["pool requests: {:.1f}K".format(_df["ride_hail_pool_requests"]/_unit),
              "solo requests: {:.1f}K".format(_df["ride_hail_solo_requests"]/_unit),
              "pool: {:.1%} ({:.1%})".format(pool_tot_share, pool_share),
              "solo: {:.1%}".format(solo_share),
              "unmatched: {:.1%}".format(unmatched_share)]
    fig = go.Figure(data=[go.Sankey(
        # Define nodes
        node=dict(
            pad=15,
            thickness=15,
            line=dict(color="black", width=0.5),
            label=labels
        ),
        # Add links
        link=dict(
            source=[0, 0, 0, 1, 1],
            target=[2, 3, 4, 3, 4],
            value=[_df["multi_passenger_pool_trips"],
                   _df["one_passenger_pool_trips"],
                   _df["unmatched_pool_requests"],
                   _df["solo_trips"],
                   _df["unmatched_solo_requests"]]
        ))])
    fig.update_layout(title_text="Sankey Diagram For Pooling", font_size=10)
    fig.write_image("{}.pooling-sankey.png".format(_local_filename_itr))