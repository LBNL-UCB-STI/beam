import sys
import json
import plotly.graph_objects as go
import pandas as pd
import required_module_installer as required

def get_pooling_metrics(filename):
    data = pd.read_csv(filename, sep=",", index_col=None, header=0)
    modeChoice = data.loc[data['type'] == 'ModeChoice'].dropna(how='all', axis=1)
    pathTraversal = data.loc[data['type'] == 'PathTraversal'].dropna(how='all', axis=1)
    ride_hail_mc = modeChoice[modeChoice['mode'].str.startswith('ride_hail')]
    ride_hail_mc_users = set(ride_hail_mc['person'])
    data2 = data[(data['type'].isin(['PathTraversal']) & data['vehicle'].str.startswith('rideHailVehicle')) |
                 (data['type'].isin(['ModeChoice', 'PersonEntersVehicle', 'PersonLeavesVehicle']) &
                  data['person'].isin(ride_hail_mc_users))]
    del data
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

    for row in data2.itertuples():
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
            del mode_choice_attempt[person]
        elif event == "PathTraversal":
            if not vehicle.startswith("rideHailVehicle"):
                continue
            if int(passengers) < 1:
                sum_deadheading_distance_traveled += float(distance)
            sum_ride_hail_distance_traveled += float(distance)
    del data2

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

    out = {
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
        "deadheading_per_ride_hail_trips": deadheading_per_ride_hail_trips
    }

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

    modeChoiceTotals = modeChoice.groupby('mode').agg({'person': 'count', 'length': 'sum'})
    for mode in modeChoiceTotals.index:
        out[mode+'_counts'] = int(modeChoiceTotals.loc[mode,'person'])

    pathTraversalModes = pathTraversal.groupby('mode_extended').agg({'vehicleMiles':'sum','primaryFuel': 'sum','secondaryFuel': 'sum', 'passengerMiles':'sum'})

    for mode in pathTraversalModes.index:
        out['VMT_' + mode] = float(pathTraversalModes.loc[mode,'vehicleMiles'])
        out['PMT_' + mode] = float(pathTraversalModes.loc[mode,'passengerMiles'])
        out['Energy_' + mode] = float(pathTraversalModes.loc[mode,'primaryFuel'] + pathTraversalModes.loc[mode,'secondaryFuel'])

    primaryFuelTypes = pathTraversal.groupby('primaryFuelType').agg({'primaryFuel': 'sum'})
    secondaryFuelTypes = pathTraversal.groupby('secondaryFuelType').agg({'secondaryFuel': 'sum'})

    for fueltype in primaryFuelTypes.index:
        out['totalEnergy_' + fueltype] = float(primaryFuelTypes.loc[fueltype,'primaryFuel'])

    for fuelType in secondaryFuelTypes.index:
        if fuelType != 'None':
            out['totalEnergy_' + fuelType] += float(secondaryFuelTypes.loc[fueltype,'secondaryFuel'])

    out['rh_empty_miles'] = float(pathTraversal.loc[pathTraversal['isRH'] & (pathTraversal['trueOccupancy'] == 0),'miles'].sum())

    out['VMT_cav_empty'] = float(pathTraversal.loc[~pathTraversal['isRH'] & pathTraversal['isCAV'] & (pathTraversal['trueOccupancy'] == 0),'miles'].sum())

    out['Low_VMT'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L1'),'miles'].sum())

    out['High_VMT'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L3'),'miles'].sum())

    out['CAV_VMT'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L5'),'miles'].sum())
    return out


def get_pooling_sankey_diagram(_df, _name, _unit=1000.0):
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
    fig.write_image("{}/pooling-metrics-sankey.png".format(_name))


if __name__ == '__main__':
    required.installAll()
    print("getting pooling metrics from events file: " + sys.argv[1])
    pooling_metrics = get_pooling_metrics(sys.argv[1])
    name = sys.argv[1].rsplit('/', 1)[0]
    with open('{}/pooling-metrics.json'.format(name), 'w') as f:
        json.dump(pooling_metrics, f)
    unit = sys.argv[2] if len(sys.argv) == 3 else 1000.0
    get_pooling_sankey_diagram(pooling_metrics, name, unit)
    print(json.dumps(pooling_metrics, indent=4))
    print("done")