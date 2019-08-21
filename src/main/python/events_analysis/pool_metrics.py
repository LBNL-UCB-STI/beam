import sys
import json


def get_pooling_metrics(filename):
    import pandas as pd
    data = pd.read_csv(filename, sep=",", dtype=str)
    ride_hail_mc = data[data['type'].isin(['ModeChoice']) & data['mode'].str.startswith('ride_hail')]
    ride_hail_mc_users = set(ride_hail_mc['person'])
    data2 = data[(data['type'].isin(['PathTraversal']) & data['vehicle'].str.startswith('rideHailVehicle')) |
                 (data['type'].isin(['ModeChoice', 'PersonEntersVehicle', 'PersonLeavesVehicle']) &
                  data['person'].isin(ride_hail_mc_users))]

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
            if person.startswith("rideHailAgent"):
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

    tot_pool_trips = count_of_multi_passenger_pool_trips + count_of_one_passenger_pool_trips
    tot_rh_trips = tot_pool_trips + count_of_solo_trips
    tot_rh_unmatched = count_of_unmatched_pool_requests + count_of_unmatched_solo_requests
    tot_rh_requests = tot_rh_trips + tot_rh_unmatched

    multi_passengers_trips_per_pool_trips = 0 if tot_pool_trips == 0 else count_of_multi_passenger_pool_trips / tot_pool_trips
    multi_passengers_trips_per_ride_hail_trips = 0 if tot_rh_trips == 0 else count_of_multi_passenger_pool_trips / tot_rh_trips
    unmatched_per_ride_hail_requests = 0 if tot_rh_requests == 0 else tot_rh_unmatched / tot_rh_requests
    deadheading_per_ride_hail_trips = 0 if sum_ride_hail_distance_traveled == 0 else sum_deadheading_distance_traveled / sum_ride_hail_distance_traveled

    return {
        "count_of_multi_passenger_pool_trips": count_of_multi_passenger_pool_trips,
        "count_of_one_passenger_pool_trips": count_of_one_passenger_pool_trips,
        "count_of_solo_trips": count_of_solo_trips,
        "count_of_unmatched_pool_requests": count_of_unmatched_pool_requests,
        "count_of_unmatched_solo_requests": count_of_unmatched_solo_requests,
        "sum_deadheading_distance_traveled": sum_deadheading_distance_traveled,
        "sum_ride_hail_distance_traveled": sum_ride_hail_distance_traveled,
        "multi_passengers_trips_per_pool_trips": multi_passengers_trips_per_pool_trips,
        "multi_passengers_trips_per_ride_hail_trips": multi_passengers_trips_per_ride_hail_trips,
        "unmatched_per_ride_hail_requests": unmatched_per_ride_hail_requests,
        "deadheading_per_ride_hail_trips": deadheading_per_ride_hail_trips
    }


print("getting pooling metrics from events file: " + sys.argv[1])
output = get_pooling_metrics(sys.argv[1])
print(json.dumps(output, indent=4))
print("done")