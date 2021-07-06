"""
this is compilation of useful functions that might be helpful to analyse BEAM-related data
"""

import matplotlib.pyplot as plt
import numpy as np
import time
import urllib
import pandas as pd
import re

import statistics

from urllib.error import HTTPError
from urllib import request


def get_output_path_from_s3_url(s3_url):
    """
    transform s3 output path (from beam runs spreadsheet) into path to s3 output
    that may be used as part of path to the file.

    s3path = get_output_path_from_s3_url(s3url)
    beam_log_path = s3path + '/beamLog.out'
    """

    return s3_url \
        .strip() \
        .replace("s3.us-east-2.amazonaws.com/beam-outputs/index.html#", "beam-outputs.s3.amazonaws.com/")


def grep_beamlog(s3url, keywords):
    """
    look for keywords in beamLog.out file of specified run
    found rows with keywords will be printed out
    """

    s3path = get_output_path_from_s3_url(s3url)
    url = s3path + "/beamLog.out"
    file = urllib.request.urlopen(url)
    for b_line in file.readlines():
        line = b_line.decode("utf-8")
        for keyword in keywords:
            if keyword in line:
                print(line)


def parse_config(s3url, complain=True):
    """
    parse beam config of beam run.

    :param s3url: url to s3 output
    :param complain: it will complain if there are many config values found with the same name
    :return: dictionary config key -> config value
    """

    s3path = get_output_path_from_s3_url(s3url)
    url = s3path + "/fullBeamConfig.conf"
    config = urllib.request.urlopen(url)

    config_keys = ["flowCapacityFactor", "speedScalingFactor", "quick_fix_minCarSpeedInMetersPerSecond",
                   "activitySimEnabled", "transitCapacity",
                   "minimumRoadSpeedInMetersPerSecond", "fractionOfInitialVehicleFleet",
                   "agentSampleSizeAsFractionOfPopulation",
                   "simulationName", "directory", "generate_secondary_activities", "lastIteration",
                   "fractionOfPeopleWithBicycle",
                   "parkingStallCountScalingFactor", "parkingPriceMultiplier", "parkingCostScalingFactor", "queryDate",
                   "transitPrice", "transit_crowding", "transit_crowding_percentile",
                   "maxLinkLengthToApplySpeedScalingFactor", "max_destination_distance_meters",
                   "max_destination_choice_set_size",
                   "transit_crowding_VOT_multiplier", "transit_crowding_VOT_threshold",
                   "activity_file_path", "intercept_file_path", "additional_trip_utility",
                   "ModuleProbability_1", "ModuleProbability_2", "ModuleProbability_3", "ModuleProbability_4",
                   "BUS-DEFAULT", "RAIL-DEFAULT", "SUBWAY-DEFAULT"]
    intercept_keys = ["bike_intercept", "car_intercept", "drive_transit_intercept", "ride_hail_intercept",
                      "ride_hail_pooled_intercept", "ride_hail_transit_intercept", "walk_intercept",
                      "walk_transit_intercept", "transfer"]

    config_map = {}
    default_value = ""

    for conf_key in config_keys:
        config_map[conf_key] = default_value

    def set_value(key, line_value):
        value = line_value.strip().replace("\"", "")

        if key not in config_map:
            config_map[key] = value
        else:
            old_val = config_map[key]
            if old_val == default_value or old_val.strip() == value.strip():
                config_map[key] = value
            else:
                if complain:
                    print("an attempt to rewrite config value with key:", key)
                    print("   value in the map  \t", old_val)
                    print("   new rejected value\t", value)

    physsim_names = ['JDEQSim', 'BPRSim', 'PARBPRSim', 'CCHRoutingAssignment']

    def look_for_physsim_type(config_line):
        for physsim_name in physsim_names:
            if 'name={}'.format(physsim_name) in config_line:
                set_value("physsim_type", "physsim_type = {}".format(physsim_name))

    for b_line in config.readlines():
        line = b_line.decode("utf-8").strip()

        look_for_physsim_type(line)

        for ckey in config_keys:
            if ckey + "=" in line or ckey + "\"=" in line or '"' + ckey + ":" in line:
                set_value(ckey, line)

        for ikey in intercept_keys:
            if ikey in line:
                set_value(ikey, line)

    return config_map


def get_calibration_text_data(s3url, commit=""):
    """
    calculate the csv row with values for pasting them into spreadsheet with beam runs

    :param s3url: url to beam run
    :param commit: git commit to insert into resulting csv string
    :return: the csv string
    """

    def get_realized_modes_as_str(full_path, data_file_name='referenceRealizedModeChoice.csv'):
        if data_file_name not in full_path:
            path = get_output_path_from_s3_url(full_path) + "/" + data_file_name
        else:
            path = get_output_path_from_s3_url(full_path)

        df = pd.read_csv(path,
                         names=['bike', 'car', 'cav', 'drive_transit', 'ride_hail', 'ride_hail_pooled',
                                'ride_hail_transit',
                                'walk', 'walk_transit'])
        last_row = df.tail(1)
        car = float(last_row['car'])
        walk = float(last_row['walk'])
        bike = float(last_row['bike'])
        ride_hail = float(last_row['ride_hail'])
        ride_hail_transit = float(last_row['ride_hail_transit'])
        walk_transit = float(last_row['walk_transit'])
        drive_transit = float(last_row['drive_transit'])
        ride_hail_pooled = float(last_row['ride_hail_pooled'])
        # car	walk	bike	ride_hail	ride_hail_transit	walk_transit	drive_transit	ride_hail_pooled
        result = "%f,%f,%f,%f,%f,%f,%f,%f" % (
            car, walk, bike, ride_hail, ride_hail_transit, walk_transit, drive_transit, ride_hail_pooled)
        return result

    print("order: car | walk | bike | ride_hail | ride_hail_transit | walk_transit | drive_transit | ride_hail_pooled")
    print("")

    print('ordered realized mode choice:')
    print('ordered commute realized mode choice:')
    modes_section = get_realized_modes_as_str(s3url)
    print(modes_section)
    print(get_realized_modes_as_str(s3url, 'referenceRealizedModeChoice_commute.csv'))
    print("")

    config = parse_config(s3url)

    def get_config_value(conf_value_name):
        return config.get(conf_value_name, '=default').split('=')[-1]

    intercepts = ["car_intercept", "walk_intercept", "bike_intercept", "ride_hail_intercept",
                  "ride_hail_transit_intercept",
                  "walk_transit_intercept", "drive_transit_intercept", "ride_hail_pooled_intercept", "transfer"]
    print('order of intercepts:', "\n\t\t ".join(intercepts))
    intercepts_sections = ', '.join(get_config_value(x) for x in intercepts)
    print(intercepts_sections)
    print("")

    config_ordered = ["lastIteration", "agentSampleSizeAsFractionOfPopulation", "flowCapacityFactor",
                      "speedScalingFactor",
                      "quick_fix_minCarSpeedInMetersPerSecond", "minimumRoadSpeedInMetersPerSecond",
                      "fractionOfInitialVehicleFleet", "transitCapacity", "fractionOfPeopleWithBicycle",
                      "parkingStallCountScalingFactor", "transitPrice", "transit_crowding_VOT_multiplier",
                      "transit_crowding_VOT_threshold", "additional_trip_utility"]
    print('order of config values:', "\n\t\t ".join(config_ordered))
    config_section = ','.join(get_config_value(x) for x in config_ordered)
    print(config_section)
    print("")

    print('the rest of configuration:')
    for key, value in config.items():
        if 'intercept' not in key and key not in config_ordered:
            print(value)

    print("")
    grep_beamlog(s3url, ["Total number of links", "Number of persons:"])

    return "{}, ,{},{}, , ,{}, ,{}".format(config_section, commit, s3url, modes_section, intercepts_sections)


def plot_simulation_vs_google_speed_comparison(s3url, iteration, compare_vs_3am, title=""):
    """
    if google requests was enabled for specified run for specified iteration, then
    plots comparison of google speeds vs simulation speeds for the same set of routes

    :param iteration: iteration of simulation
    :param s3url: url to s3 output
    :param compare_vs_3am: if comparison should be done vs google 3am speeds (relaxed speed) instead of regular route time
    :param title: main title for all plotted graphs. useful for future copy-paste to distinguish different simulations
    """

    s3path = get_output_path_from_s3_url(s3url)
    google_tt = pd.read_csv(s3path + "/ITERS/it.{0}/{0}.googleTravelTimeEstimation.csv".format(iteration))

    google_tt_3am = google_tt[google_tt['departureTime'] == 3 * 60 * 60].copy()
    google_tt_rest = google_tt[
        (google_tt['departureTime'] != 3 * 60 * 60) & (google_tt['departureTime'] < 24 * 60 * 60)].copy()

    google_tt_column = 'googleTravelTimeWithTraffic'
    google_tt_column3am = 'googleTravelTimeWithTraffic'

    def get_speed(distance, travel_time):
        # travel time may be -1 for some google requests because of some google errors
        if travel_time <= 0:
            return 0
        else:
            return distance / travel_time

    def get_uid(row):
        return "{}:{}:{}:{}:{}".format(row['vehicleId'], row['originLat'], row['originLng'], row['destLat'],
                                       row['destLng'])

    if compare_vs_3am:
        google_tt_3am['googleDistance3am'] = google_tt_3am['googleDistance']
        google_tt_3am['google_api_speed_3am'] = google_tt_3am.apply(
            lambda row: (get_speed(row['googleDistance'], row[google_tt_column3am])), axis=1)

        google_tt_3am['uid'] = google_tt_3am.apply(get_uid, axis=1)
        google_tt_3am = google_tt_3am.groupby('uid')['uid', 'google_api_speed_3am', 'googleDistance3am'] \
            .agg(['min', 'mean', 'max']).copy()
        google_tt_3am.reset_index(inplace=True)

    google_tt_rest['google_api_speed'] = google_tt_rest.apply(
        lambda row: (get_speed(row['googleDistance'], row[google_tt_column])), axis=1)
    google_tt_rest['sim_speed'] = google_tt_rest.apply(lambda row: (get_speed(row['legLength'], row['simTravelTime'])),
                                                       axis=1)
    google_tt_rest['uid'] = google_tt_rest.apply(get_uid, axis=1)

    df = google_tt_rest \
        .groupby(['uid', 'departureTime'])[[google_tt_column, 'googleDistance', 'google_api_speed', 'sim_speed']] \
        .agg({google_tt_column: ['min', 'mean', 'max'],
              'googleDistance': ['min', 'mean', 'max'],
              'google_api_speed': ['min', 'mean', 'max'], 'sim_speed': ['min']}) \
        .copy()

    df.reset_index(inplace=True)

    if compare_vs_3am:
        df = df.join(google_tt_3am.set_index('uid'), on='uid')

    df['departure_hour'] = df['departureTime'] // 3600

    df.columns = ['{}_{}'.format(x[0], x[1]) for x in df.columns]
    df['sim_speed'] = df['sim_speed_min']

    fig, (ax0, ax1) = plt.subplots(1, 2, figsize=(22, 5))
    fig.tight_layout(pad=0.1)
    fig.subplots_adjust(wspace=0.15, hspace=0.1)
    fig.suptitle(title, y=1.11)

    title0 = "Trip-by-trip speed comparison"
    title1 = "Hour-by-hour average speed comparison"
    if compare_vs_3am:
        title0 = title0 + " at 3am"
        title1 = title1 + " at 3am"

    def plot_hist(google_column_name, label):
        df[label] = df['sim_speed'] - df[google_column_name]
        df[label].plot.kde(bw_method=0.2, ax=ax0)

    if compare_vs_3am:
        plot_hist('google_api_speed_3am_max', 'Maximum estimate')
    else:
        plot_hist('google_api_speed_max', 'Maximum estimate')
        plot_hist('google_api_speed_mean', 'Mean estimate')
        plot_hist('google_api_speed_min', 'Minimum estimate')

    ax0.axvline(0, color="black", linestyle="--")
    ax0.set_title(title0)
    ax0.legend(loc='upper left')
    ax0.set_xlabel('Difference in speed (m/s)')
    ax0.set_ylabel('Density')

    to_plot_df_speed_0 = df.groupby(['departure_hour_']).mean()
    to_plot_df_speed_0['departure_hour_'] = to_plot_df_speed_0.index

    if compare_vs_3am:
        to_plot_df_speed_0.plot(x='departure_hour_', y='google_api_speed_3am_min', label='Minimum estimate 3am', ax=ax1)
        to_plot_df_speed_0.plot(x='departure_hour_', y='google_api_speed_3am_mean', label='Mean estimate 3am', ax=ax1)
        to_plot_df_speed_0.plot(x='departure_hour_', y='google_api_speed_3am_max', label='Maximum estimate 3am', ax=ax1)
    else:
        to_plot_df_speed_0.plot(x='departure_hour_', y='google_api_speed_min', label='Minimum estimate', ax=ax1)
        to_plot_df_speed_0.plot(x='departure_hour_', y='google_api_speed_mean', label='Mean estimate', ax=ax1)
        to_plot_df_speed_0.plot(x='departure_hour_', y='google_api_speed_max', label='Maximum estimate', ax=ax1)

    to_plot_df_speed_0.plot(x='departure_hour_', y='sim_speed', label='Simulated Speed', ax=ax1)

    ax1.legend(loc='upper right')
    ax1.set_title(title1)
    ax1.set_xlabel('Hour of day')
    ax1.set_ylabel('Speed (m/s)')


def print_spreadsheet_rows(s3urls, commit, iteration):
    calibration_text = []

    for s3url in s3urls:
        main_text = get_calibration_text_data(s3url, commit=commit)

        fake_walkers_file_name = "{}.fake_real_walkers.csv.gz".format(iteration)
        fake_walkers = get_from_s3(s3url, fake_walkers_file_name)

        s3path = get_output_path_from_s3_url(s3url)
        replanning_path = s3path + "/ITERS/it.{0}/{0}.replanningEventReason.csv".format(iteration)
        replanning_reasons = pd.read_csv(replanning_path)
        print('\nreplanning_reasons:\n', replanning_reasons, '\n\n')
        walk_transit_exhausted = \
            replanning_reasons[replanning_reasons['ReplanningReason'] == 'ResourceCapacityExhausted WALK_TRANSIT'][
                'Count'].values[0]

        calibration_text.append((main_text, fake_walkers, walk_transit_exhausted))

    print("\n\nspreadsheet text:")
    for (text, _, _) in calibration_text:
        print(text)
    print("\n")

    print("\n\nfake walkers:")
    for (_, fake_walkers, _) in calibration_text:
        if fake_walkers is None:
            print("Not Available")
        else:
            print(fake_walkers['fake_walkers_ratio'].values[0] * 100)
    print("\n")

    print("\n\nResourceCapacityExhausted WALK_TRANSIT:")
    for (_, _, text) in calibration_text:
        print(text)
    print("\n")


def plot_fake_real_walkers(title, fake_walkers, real_walkers, threshold):
    fig, axs = plt.subplots(2, 2, figsize=(24, 4 * 2))
    fig.tight_layout()
    fig.subplots_adjust(wspace=0.05, hspace=0.2)
    fig.suptitle(title, y=1.11)

    ax1 = axs[0, 0]
    ax2 = axs[0, 1]

    fake_walkers['length'].hist(bins=50, ax=ax1, alpha=0.3, label='fake walkers')
    real_walkers['length'].hist(bins=50, ax=ax1, alpha=0.3, label='real walkers')
    ax1.legend(loc='upper right', prop={'size': 10})
    ax1.set_title("Trip length histogram. Fake vs Real walkers. Min length of trip is {0}".format(threshold))
    ax1.axvline(5000, color="black", linestyle="--")

    fake_walkers['length'].hist(bins=50, ax=ax2, log=True, alpha=0.3, label='fake walkers')
    real_walkers['length'].hist(bins=50, ax=ax2, log=True, alpha=0.3, label='real walkers')
    ax2.legend(loc='upper right', prop={'size': 10})
    ax2.set_title(
        "Trip length histogram. Fake vs Real walkers. Logarithmic scale. Min length of trip is {0}".format(threshold))
    ax2.axvline(5000, color="black", linestyle="--")

    ax1 = axs[1, 0]
    ax2 = axs[1, 1]

    long_real_walkers = real_walkers[real_walkers['length'] >= threshold]
    number_of_top_alternatives = 5
    walkers_by_alternative = long_real_walkers.groupby('availableAlternatives')['length'].count().sort_values(
        ascending=False)
    top_alternatives = set(
        walkers_by_alternative.reset_index()['availableAlternatives'].head(number_of_top_alternatives))

    for alternative in top_alternatives:
        label = str(list(set(alternative.split(':')))).replace('\'', '')[1:-1]
        selected = long_real_walkers[long_real_walkers['availableAlternatives'] == alternative]['length']
        selected.hist(bins=50, ax=ax1, alpha=0.4, linewidth=4, label=label)
        selected.hist(bins=20, ax=ax2, log=True, histtype='step', linewidth=4, label=label)

    ax1.set_title("Length histogram of top {} alternatives of real walkers".format(number_of_top_alternatives))
    ax1.legend(loc='upper right', prop={'size': 10})
    ax2.set_title(
        "Length histogram of top {} alternatives of real walkers. Logarithmic scale".format(number_of_top_alternatives))
    ax2.legend(loc='upper right', prop={'size': 10})


def get_fake_real_walkers(s3url, iteration, threshold=2000):
    s3path = get_output_path_from_s3_url(s3url)
    events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)

    start_time = time.time()
    modechoice = pd.concat([df[(df['type'] == 'ModeChoice') | (df['type'] == 'Replanning')]
                            for df in pd.read_csv(events_file_path, low_memory=False, chunksize=100000)])
    print("events file url:", events_file_path)
    print("loading took %s seconds" % (time.time() - start_time))

    count_of_replanning = modechoice[modechoice['type'] == 'Replanning'].shape[0]
    modechoice = modechoice[modechoice['type'] == 'ModeChoice']
    count_of_modechouces = len(modechoice) - count_of_replanning

    walk_modechoice = modechoice[modechoice['mode'] == 'walk'].copy()

    def is_real(row):
        if row['length'] < threshold:
            return True

        alternatives = set(row['availableAlternatives'].split(':'))

        if len(alternatives) == 0:
            print('+1')
            return False

        if len(alternatives) == 1 and ('WALK' in alternatives or 'NaN' in alternatives):
            return False

        return True

    walk_modechoice[['availableAlternatives']] = walk_modechoice[['availableAlternatives']].fillna('NaN')
    walk_modechoice['isReal'] = walk_modechoice.apply(is_real, axis=1)

    fake_walkers = walk_modechoice[~walk_modechoice['isReal']]
    real_walkers = walk_modechoice[walk_modechoice['isReal']]

    plot_fake_real_walkers(s3url, fake_walkers, real_walkers, threshold)

    columns = ['real_walkers', 'real_walkers_ratio', 'fake_walkers', 'fake_walkers_ratio', 'total_modechoice']
    values = [len(real_walkers), len(real_walkers) / count_of_modechouces,
              len(fake_walkers), len(fake_walkers) / count_of_modechouces, count_of_modechouces]

    walkers = pd.DataFrame(np.array([values]), columns=columns)
    return walkers


def get_from_s3(s3url, file_name,
                s3_additional_output='scripts_output'):
    s3path = get_output_path_from_s3_url(s3url)
    path = "{}/{}/{}".format(s3path, s3_additional_output, file_name)
    df = None
    try:
        df = pd.read_csv(path, low_memory=False)
    except HTTPError:
        print('File does not exist by path:', path)

    return df


def save_to_s3(s3url, df, file_name,
               aws_access_key_id, aws_secret_access_key,
               output_bucket='beam-outputs', s3_additional_output='scripts_output'):
    import boto3
    s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id, aws_secret_access_key=aws_secret_access_key)

    require_string = 'index.html#'
    if require_string not in s3url:
        print(
            's3url does not contain "{}". That means there is no way to save df. Cancelled.'.format(
                require_string))
    else:
        df.to_csv(file_name)
        folder_path = s3url.split('#')[1].strip()
        out_path = "{}/{}/{}".format(folder_path, s3_additional_output, file_name)
        s3.meta.client.upload_file(file_name, output_bucket, out_path)
        print('saved to s3: ', out_path)


def read_persons_vehicles_trips(s3url, iteration):
    def read_pte_pelv_for_walk_transit(nrows=None):
        s3path = get_output_path_from_s3_url(s3url)
        events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)

        start_time = time.time()
        columns = ['type', 'time', 'vehicle', 'driver', 'arrivalTime', 'departureTime', 'length', 'vehicleType',
                   'person']
        events = pd.concat([df[(df['type'] == 'PersonEntersVehicle') |
                               (df['type'] == 'PathTraversal') |
                               (df['type'] == 'PersonLeavesVehicle')][columns]
                            for df in pd.read_csv(events_file_path, low_memory=False, chunksize=100000, nrows=nrows)])
        print("events loading took %s seconds" % (time.time() - start_time))

        ptes = events[events['type'] == 'PathTraversal']

        drivers = set(ptes[ptes['vehicleType'].isin(walk_transit_modes)]['driver'])
        transit_vehicles = set(ptes[ptes['vehicleType'].isin(walk_transit_modes)]['vehicle'])

        events = events[~events['person'].isin(drivers)]
        events = events[events['vehicle'].isin(transit_vehicles)]

        ptes = events[events['type'] == 'PathTraversal']
        pelvs = events[(events['type'] == 'PersonEntersVehicle') | (events['type'] == 'PersonLeavesVehicle')]

        print('events:', events.shape)
        print('pte:', ptes.shape)
        print('pelv:', pelvs.shape)

        return ptes, pelvs

    walk_transit_modes = {'BUS-DEFAULT', 'SUBWAY-DEFAULT', 'RAIL-DEFAULT'}

    (pte, pelv) = read_pte_pelv_for_walk_transit()

    person_trips = pelv.groupby('person')[['type', 'time', 'vehicle']].agg(list).copy()
    print('person_trips:', person_trips.shape)

    def get_dict_departure_to_index(row):
        depart = row['departureTime']
        return {x: i for x, i in zip(depart, range(len(depart)))}

    vehicles_trips = pte.groupby('vehicle')[['arrivalTime', 'departureTime', 'length', 'vehicleType']].agg(list).copy()
    vehicles_trips['departureToIndex'] = vehicles_trips.apply(get_dict_departure_to_index, axis=1)
    print('vehicles_trips:', vehicles_trips.shape)

    def calc_person_trips_distances(row, transit_modes, vehicles_trips_df):
        ttypes = row['type']
        ttimes = row['time']
        tvehicles = row['vehicle']

        veh_entered = None
        time_entered = None
        trips_per_mode = {x: 0.0 for x in transit_modes}

        if len(ttypes) != len(ttimes) or len(ttypes) != len(tvehicles):
            print('PROBLEMS. lengts are not equal:', row)
            return [trips_per_mode[tm] for tm in transit_modes]

        for (ttype, ttime, tvehicle) in zip(ttypes, ttimes, tvehicles):
            if ttype == 'PersonEntersVehicle':
                veh_entered = tvehicle
                time_entered = ttime

            if ttype == 'PersonLeavesVehicle':
                if veh_entered is None:
                    pass
                elif veh_entered != tvehicle:
                    print('PROBLEMS. left different vehicle:', row)
                else:
                    veh_trip = vehicles_trips_df.loc[tvehicle]
                    veh_type = veh_trip['vehicleType'][0]
                    arrivals = veh_trip['arrivalTime']
                    lenghts = veh_trip['length']
                    idx = veh_trip['departureToIndex'].get(time_entered)
                    trip_len = 0

                    while len(arrivals) > idx and arrivals[idx] <= ttime:
                        trip_len = trip_len + lenghts[idx]
                        idx = idx + 1

                    trips_per_mode[veh_type] = trips_per_mode[veh_type] + trip_len

        return [trips_per_mode[tm] for tm in transit_modes]

    transit_modes_names = list(walk_transit_modes)

    person_trips[transit_modes_names] = person_trips.apply(
        lambda row: calc_person_trips_distances(row, transit_modes_names, vehicles_trips), axis=1, result_type="expand")

    return person_trips, vehicles_trips


def plot_calibration_parameters(title_to_s3url,
                                suptitle="", figsize=(23, 6), rot=70,
                                calibration_parameters=None,
                                removal_probabilities=None):
    if calibration_parameters is None:
        calibration_parameters = ['additional_trip_utility', 'walk_transit_intercept']

    calibration_values = []

    for (title, s3url) in title_to_s3url:
        config = parse_config(s3url, complain=False)

        def get_config_value(conf_value_name):
            return config.get(conf_value_name, '=default').split('=')[-1]

        param_values = [title]
        for param in calibration_parameters:
            param_value = get_config_value(param)
            param_values.append(float(param_value))

        calibration_values.append(param_values)

    calibration_parameters.insert(0, 'name')
    result = pd.DataFrame(calibration_values, columns=calibration_parameters)

    linewidth = 4
    removal_probabilities_color = 'green'

    ax = result.plot(x='name', figsize=figsize, rot=rot, linewidth=linewidth)

    # for (idx, params) in zip(range(len(calibration_values)), calibration_values):
    #     for param in params[1:]:
    #         plt.annotate(param, (idx, param))  # , textcoords="offset points", xytext=(0,10), ha='center')

    if removal_probabilities:
        ax.plot(np.NaN, label='removal probabilities (right scale)',
                color=removal_probabilities_color, linewidth=linewidth)

    ax.set_title('calibration parameters {}'.format(suptitle))
    ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left')

    ax.grid('on', which='major', axis='y')

    if removal_probabilities:
        ax2 = ax.twinx()
        ax2.plot(range(len(removal_probabilities)), removal_probabilities,
                 color=removal_probabilities_color, alpha=0.5, linewidth=linewidth)


def analyze_fake_walkers(s3url, iteration, threshold=2000, title="", modechoice=None):
    def load_modechoices(events_file_path, chunksize=100000):
        start_time = time.time()
        df = pd.concat(
            [df[df['type'] == 'ModeChoice'] for df in
             pd.read_csv(events_file_path, low_memory=False, chunksize=chunksize)])
        print("events file url:", events_file_path)
        print("modechoice loading took %s seconds" % (time.time() - start_time))
        return df

    if modechoice is None:
        s3path = get_output_path_from_s3_url(s3url)
        file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)
        modechoice = load_modechoices(file_path)

    is_fake = (modechoice['length'] >= threshold) & (
            (modechoice['availableAlternatives'] == 'WALK') | (modechoice['availableAlternatives'].isnull()))

    fake_walkers = modechoice[(modechoice['mode'] == 'walk') & is_fake]
    real_walkers = modechoice[(modechoice['mode'] == 'walk') & (~is_fake)]

    fig, axs = plt.subplots(2, 2, figsize=(24, 4 * 2))
    fig.tight_layout()
    fig.subplots_adjust(wspace=0.05, hspace=0.2)
    fig.suptitle(title, y=1.11)

    ax1 = axs[0, 0]
    ax2 = axs[0, 1]

    fake_walkers['length'].hist(bins=200, ax=ax1, alpha=0.3, label='fake walkers')
    real_walkers['length'].hist(bins=200, ax=ax1, alpha=0.3, label='real walkers')
    ax1.legend(loc='upper right', prop={'size': 10})
    ax1.set_title("Trip length histogram. Fake vs Real walkers. Min length of trip is {0}".format(threshold))
    ax1.axvline(5000, color="black", linestyle="--")

    fake_walkers['length'].hist(bins=200, ax=ax2, log=True, alpha=0.3, label='fake walkers')
    real_walkers['length'].hist(bins=200, ax=ax2, log=True, alpha=0.3, label='real walkers')
    ax2.legend(loc='upper right', prop={'size': 10})
    ax2.set_title(
        "Trip length histogram. Fake vs Real walkers. Logarithmic scale. Min length of trip is {0}".format(threshold))
    ax2.axvline(5000, color="black", linestyle="--")

    ax1 = axs[1, 0]
    ax2 = axs[1, 1]

    long_real_walkers = real_walkers[real_walkers['length'] >= threshold]
    number_of_top_alternatives = 5
    walkers_by_alternative = long_real_walkers.groupby('availableAlternatives')['length'].count().sort_values(
        ascending=False)
    top_alternatives = set(
        walkers_by_alternative.reset_index()['availableAlternatives'].head(number_of_top_alternatives))

    for alternative in top_alternatives:
        selected = long_real_walkers[long_real_walkers['availableAlternatives'] == alternative]['length']
        selected.hist(bins=200, ax=ax1, alpha=0.4, linewidth=4, label=alternative)
        selected.hist(bins=20, ax=ax2, log=True, histtype='step', linewidth=4, label=alternative)

    ax1.set_title("Length histogram of top {} alternatives of real walkers".format(number_of_top_alternatives))
    ax1.legend(loc='upper right', prop={'size': 10})
    ax2.set_title(
        "Length histogram of top {} alternatives of real walkers. Logarithmic scale".format(number_of_top_alternatives))
    ax2.legend(loc='upper right', prop={'size': 10})

    number_of_fake_walkers = fake_walkers.shape[0]
    number_of_real_walkers = real_walkers.shape[0]
    number_of_all_modechoice = modechoice.shape[0]

    print('number of all modechoice events', number_of_all_modechoice)
    print('number of real walkers, real walkers of all modechoice events :')
    print(number_of_real_walkers, number_of_real_walkers / number_of_all_modechoice)
    print('number of FAKE walkers, FAKE walkers of all modechoice events :')
    print(number_of_fake_walkers, number_of_fake_walkers / number_of_all_modechoice)

    return [number_of_real_walkers, number_of_real_walkers / number_of_all_modechoice,
            number_of_fake_walkers, number_of_fake_walkers / number_of_all_modechoice, number_of_all_modechoice]


def plot_modechoice_comparison(title_to_s3url, benchmark_url, benchmark_name="benchmark", iteration=0,
                               do_fake_walk_analysis=False, fake_walkers=None):
    modes = ['bike', 'car', 'drive_transit', 'ride_hail',
             'ride_hail_pooled', 'ride_hail_transit', 'walk_transit', 'walk']

    if do_fake_walk_analysis:
        modes = modes + ['walk_fake', 'walk_real']

    def get_realized_modes(s3url, data_file_name='realizedModeChoice.csv', fake_walkers_dict=None):
        path = get_output_path_from_s3_url(s3url) + "/" + data_file_name
        df = pd.read_csv(path)
        tail = df.tail(1).copy()

        exist_columns = set(tail.columns)
        for m in modes:
            if m not in exist_columns:
                tail[m] = 0.0
            else:
                tail[m] = tail[m].astype(float)

        if do_fake_walk_analysis:
            fake_walkers_current = None
            if fake_walkers_dict:
                fake_walkers_current = fake_walkers_dict.get(s3url)
            if not fake_walkers_current:
                fake_walkers_current = analyze_fake_walkers(s3url, iteration, threshold=2000, title="", modechoice=None)

            walk_real_perc = fake_walkers_current[1]
            walk_fake_perc = fake_walkers_current[3]

            total_perc = walk_real_perc + walk_fake_perc
            one_perc_of_walk = int(tail['walk']) / total_perc

            print("walk: {} 1%: {} walk_real: {} walk_fake: {}".format(
                int(tail['walk']), one_perc_of_walk,
                one_perc_of_walk * walk_real_perc,
                one_perc_of_walk * walk_fake_perc))

            tail['walk_real'] = one_perc_of_walk * walk_real_perc
            tail['walk_fake'] = one_perc_of_walk * walk_fake_perc

        return tail[modes]

    benchmark = get_realized_modes(benchmark_url, fake_walkers_dict=fake_walkers).reset_index(drop=True)

    benchmark_absolute = benchmark.copy()
    benchmark_absolute['name'] = benchmark_name

    zeros = benchmark_absolute.copy()
    for mode in modes:
        zeros[mode] = 0.0

    modechoices_absolute = [benchmark_absolute]
    modechoices_difference = [zeros]
    modechoices_diff_in_percentage = [zeros]

    for (name, url) in title_to_s3url:
        modechoice = get_realized_modes(url, fake_walkers_dict=fake_walkers).reset_index(drop=True)

        modechoice_absolute = modechoice.copy()
        modechoice_absolute['name'] = name
        modechoices_absolute.append(modechoice_absolute)

        modechoice = modechoice.sub(benchmark, fill_value=0)
        modechoice_perc = modechoice / benchmark * 100

        modechoice['name'] = name
        modechoices_difference.append(modechoice)

        modechoice_perc['name'] = name
        modechoices_diff_in_percentage.append(modechoice_perc)

    df_absolute = pd.concat(modechoices_absolute)
    df_diff = pd.concat(modechoices_difference)
    df_diff_perc = pd.concat(modechoices_diff_in_percentage)

    _, (ax0, ax1, ax2) = plt.subplots(3, 1, sharex='all', figsize=(20, 5 * 3))

    def plot(df, ax, title):
        df.set_index('name').plot(kind='bar', ax=ax, rot=65)
        ax.axhline(0, color='black', linewidth=0.4)
        ax.legend(loc='center left', bbox_to_anchor=(1.0, 0.5))
        ax.set_title(title)

    plot(df_absolute, ax0, "absolute values of modechoice")
    plot(df_diff, ax1, "difference between run and benchmark in absolute numbers")
    plot(df_diff_perc, ax2, "difference between run and benchmark in percentage")

    plt.suptitle("BEAM run vs benchmark. realizedModeChoice.csv")


def plot_modechoice_distance_distribution(s3url, iteration):
    s3path = get_output_path_from_s3_url(s3url)
    events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)

    start_time = time.time()
    events_file = pd.concat([df[df['type'] == 'ModeChoice']
                             for df in pd.read_csv(events_file_path, low_memory=False, chunksize=100000)])
    print("modechoice loading took %s seconds" % (time.time() - start_time))

    events_file['length'].hist(bins=100, by=events_file['mode'], figsize=(20, 12), rot=10, sharex=True)


def get_average_car_speed(s3url, iteration):
    s3path = get_output_path_from_s3_url(s3url)
    average_speed = pd.read_csv(s3path + "/AverageCarSpeed.csv")
    return average_speed[average_speed['iteration'] == iteration]['speed'].median()


def calculate_median_time_at_home(s3url, iteration, total_persons, debug_print=False):
    s3path = get_output_path_from_s3_url(s3url)
    events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)

    home_acts = pd.concat([events[events['actType'] == 'Home']
                           for events in pd.read_csv(events_file_path, low_memory=False, chunksize=10000)])

    def get_home_activity_time(row):
        if row['type'] == 'actend':
            return min(row['time'] / 3600, 24.0)
        if row['type'] == 'actstart':
            return max(row['time'] / -3600, -23.9)
        return 0

    home_acts['homeActTime'] = home_acts.apply(get_home_activity_time, axis=1)
    home_activities = ((home_acts.groupby('person')['homeActTime']).sum() + 24).reset_index()

    affected_persons = len(home_acts['person'].unique())

    all_people_home_time = list(home_activities['homeActTime']) + [24] * (total_persons - affected_persons)
    median_time_at_home = statistics.median(all_people_home_time)
    if debug_print:
        print('all people home time. len:{} sum:{} mean:{} median:{}'.format(len(all_people_home_time),
                                                                             sum(all_people_home_time),
                                                                             sum(all_people_home_time) / len(
                                                                                 all_people_home_time),
                                                                             median_time_at_home))

    return median_time_at_home


def plot_median_time_at_home(title_to_s3url, total_persons, iteration, figsize=(30, 5), debug_print=False):
    mean_time = []

    for ((title, s3url), ax_idx) in zip(title_to_s3url, range(len(title_to_s3url))):
        median_time = calculate_median_time_at_home(s3url, iteration, total_persons, debug_print)
        mean_time.append((title, median_time))

    baseline = mean_time[0][1]

    time_at_home_vs_baseline = ([], [])

    for (title, avg_time_at_home) in mean_time:
        ratio = avg_time_at_home / baseline
        time_at_home_vs_baseline[0].append(title)
        time_at_home_vs_baseline[1].append(ratio)

    fig, ax = plt.subplots(1, 1, figsize=figsize)
    x = range(len(mean_time))
    y = time_at_home_vs_baseline[1]
    plt.xticks(x, time_at_home_vs_baseline[0])
    ax.plot(x, y)
    ax.set_title("Median time at home months vs baseline")


def analyze_mode_choice_changes(title_to_s3url, benchmark_url):
    # def get_realized_modes(s3url, data_file_name='referenceRealizedModeChoice.csv'):
    def get_realized_modes(s3url, data_file_name='realizedModeChoice.csv'):
        modes = ['bike', 'car', 'cav', 'drive_transit', 'ride_hail',
                 'ride_hail_pooled', 'ride_hail_transit', 'walk', 'walk_transit']

        path = get_output_path_from_s3_url(s3url) + "/" + data_file_name
        df = pd.read_csv(path, names=modes)
        tail = df.tail(1).copy()

        for mode in modes:
            tail[mode] = tail[mode].astype(float)

        return tail

    benchmark = get_realized_modes(benchmark_url).reset_index(drop=True)

    modechoices_difference = []
    modechoices_diff_in_percentage = []

    for (name, url) in title_to_s3url:
        modechoice = get_realized_modes(url).reset_index(drop=True)
        modechoice = modechoice.sub(benchmark, fill_value=0)
        modechoice_perc = modechoice / benchmark * 100

        modechoice['name'] = name
        modechoice['sim_url'] = url
        modechoices_difference.append(modechoice)

        modechoice_perc['name'] = name
        modechoice_perc['sim_url'] = url
        modechoices_diff_in_percentage.append(modechoice_perc)

    df_diff = pd.concat(modechoices_difference)
    df_diff_perc = pd.concat(modechoices_diff_in_percentage)

    _, (ax1, ax2) = plt.subplots(2, 1, sharex='all', figsize=(20, 8))

    df_diff.set_index('name').plot(kind='bar', ax=ax1, rot=65)
    df_diff_perc.set_index('name').plot(kind='bar', ax=ax2, rot=65)

    ax1.axhline(0, color='black', linewidth=0.4)
    ax1.legend(loc='center left', bbox_to_anchor=(1.0, 0.5))
    ax1.set_title('difference between run and benchmark in absolute numbers')
    ax1.grid('on', which='major', axis='y')

    ax2.axhline(0, color='black', linewidth=0.4)
    ax2.legend(loc='center left', bbox_to_anchor=(1.0, 0.5))
    ax2.set_title('difference between run and benchmark in percentage')
    ax2.grid('on', which='major', axis='y')

    plt.suptitle("BEAM run minus benchmark run. realizedModeChoice.csv")
    return benchmark


def get_default_and_emergency_parkings(s3url, iteration):
    s3path = get_output_path_from_s3_url(s3url)
    parking_file_path = s3path + "/ITERS/it.{0}/{0}.parkingStats.csv".format(iteration)
    parking_df = pd.read_csv(parking_file_path)
    parking_df['TAZ'] = parking_df['TAZ'].astype(str)
    filtered_df = parking_df[
        (parking_df['TAZ'].str.contains('default')) | (parking_df['TAZ'].str.contains('emergency'))]
    res_df = filtered_df.groupby(['TAZ']).count().reset_index()[['TAZ', 'timeBin']] \
        .rename(columns={'timeBin': 'count'})
    return res_df


def grep_beamlog_for_errors_warnings(s3url):
    error_keywords = ["ERROR", "WARN"]
    error_patterns_for_count = [
        r".*StreetLayer - .* [0-9]*.*, skipping.*",
        r".*OsmToMATSim - Could not.*. Ignoring it.",
        r".*GeoUtilsImpl - .* Coordinate does not appear to be in WGS. No conversion will happen:.*",
        r".*InfluxDbSimulationMetricCollector - There are enabled metrics, but InfluxDB is unavailable at.*",
        r".*ClusterSystem-akka.*WARN.*PersonAgent.*didn't get nextActivity.*",
        r".*ClusterSystem-akka.*WARN.*Person Actor.*attempted to reserve ride with agent Actor.*"
        + "that was not found, message sent to dead letters.",
        r".*ClusterSystem-akka.*ERROR.*PersonAgent - State:FinishingModeChoice PersonAgent:[0-9]*[ ]*"
        + "Current tour vehicle is the same as the one being removed: [0-9]* - [0-9]*.*"
    ]

    error_count = {}
    for error in error_patterns_for_count:
        error_count[error] = 0

    print("")
    print("UNEXPECTED errors | warnings:")
    print("")

    s3path = get_output_path_from_s3_url(s3url)
    file = urllib.request.urlopen(s3path + "/beamLog.out")
    for b_line in file.readlines():
        line = b_line.decode("utf-8")

        found = False
        for error_pattern in error_patterns_for_count:
            matched = re.match(error_pattern, line)
            if bool(matched):
                found = True
                error_count[error_pattern] = error_count[error_pattern] + 1

        if found:
            continue

        for error in error_keywords:
            if error in line:
                print(line)

    print("")
    print("expected errors | warnings:")
    print("")
    for error, count in error_count.items():
        print(count, "of", error)


def get_calibration_png_graphs(s3url, first_iteration=0, last_iteration=0, png_title=None):
    s3path = get_output_path_from_s3_url(s3url)

    # ######
    # fig = plt.figure(figsize=(8, 6))
    # gs = gridspec.GridSpec(1, 2, width_ratios=[3, 1])
    # ax0 = plt.subplot(gs[0])
    # ax0.plot(x, y)
    # ax1 = plt.subplot(gs[1])
    # ax1.plot(y, x)
    # ######

    def display_two_png(path1, path2, title=png_title):
        def display_png(ax, path):
            ax_title = path.split('/')[-1] + "\n"

            ax.set_title(ax_title, pad=0.1)
            ax.axes.get_xaxis().set_visible(False)
            ax.axes.get_xaxis().labelpad = 0
            ax.axes.get_yaxis().set_visible(False)
            ax.axes.get_yaxis().labelpad = 0
            ax.imshow(plt.imread(path))

        fig, axs = plt.subplots(1, 2, figsize=(25, 10))
        fig.subplots_adjust(wspace=0.01, hspace=0.01)
        fig.tight_layout()

        display_png(axs[0], path1)
        display_png(axs[1], path2)
        plt.suptitle(title)

    display_two_png(s3path + "/stopwatch.png",
                    s3path + "/AverageCarSpeed.png")

    display_two_png(s3path + "/ITERS/it.{0}/{0}.AverageSpeed.Personal.png".format(first_iteration),
                    s3path + "/ITERS/it.{0}/{0}.AverageSpeed.Personal.png".format(last_iteration))

    display_two_png(s3path + "/referenceRealizedModeChoice.png",
                    s3path + "/referenceRealizedModeChoice_commute.png")


def analyze_vehicle_passenger_by_hour(s3url, iteration):
    s3path = get_output_path_from_s3_url(s3url)
    events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)
    plot_vehicle_type_passengets_by_hours(events_file_path)


def plot_vehicle_type_passengets_by_hours(events_file_path, chunksize=100000):
    events = pd.concat([events[events['type'] == 'PathTraversal'] for events in
                        pd.read_csv(events_file_path, low_memory=False, chunksize=chunksize)])
    events['time'] = events['time'].astype('float')
    events = events.sort_values(by='time', ascending=True)

    hour2type2num_passenger = {}
    vehicle2passengers_and_type = {}
    last_hour = 0

    def update_last_hour_vehicles():
        cur_type2num_passenger = {}
        for _, (passengers, t) in vehicle2passengers_and_type.items():
            if t not in cur_type2num_passenger:
                cur_type2num_passenger[t] = 0
            cur_type2num_passenger[t] = cur_type2num_passenger[t] + passengers
        hour2type2num_passenger[last_hour] = cur_type2num_passenger

    for index, row in events.iterrows():
        hour = int(float(row['time']) / 3600)
        vehicle_type = row['vehicleType']
        v = row['vehicle']
        num_passengers = int(row['numPassengers'])
        if vehicle_type == 'BODY-TYPE-DEFAULT':
            continue
        if hour != last_hour:
            update_last_hour_vehicles()
            last_hour = hour
            vehicle2passengers_and_type = {}
        if (v not in vehicle2passengers_and_type) or (vehicle2passengers_and_type[v][0] < num_passengers):
            vehicle2passengers_and_type[v] = (num_passengers, vehicle_type)

    update_last_hour_vehicles()
    vehicles = set()
    for hour, data in hour2type2num_passenger.items():
        for v, _ in data.items():
            vehicles.add(v)

    hours = []
    res = {}
    for h, dataForHour in hour2type2num_passenger.items():
        hours.append(h)
        for v in vehicles:
            if v not in res:
                res[v] = []
            if v not in dataForHour:
                res[v].append(0)
            else:
                res[v].append(dataForHour[v])

    res['HOUR'] = hours
    rows = int(len(vehicles) / 2)

    fig1, axes = plt.subplots(rows, 2, figsize=(25, 7 * rows))
    fig1.tight_layout(pad=0.1)
    fig1.subplots_adjust(wspace=0.25, hspace=0.1)
    res_df = pd.DataFrame(res)
    for i, v in enumerate(vehicles):
        if i < len(vehicles) - 1:
            res_df.plot(x='HOUR', y=v, ax=axes[int(i / 2)][i % 2])
        else:
            fig1, ax = plt.subplots(1, 1, figsize=(8, 7))
            fig1.tight_layout(pad=0.1)
            fig1.subplots_adjust(wspace=0.25, hspace=0.1)
            res_df.plot(x='HOUR', y=v, ax=ax)


def print_network_from(s3path, take_rows=0):
    def show_network(network_path):
        network_df = pd.read_csv(network_path)
        network_df = network_df[['attributeOrigType', 'linkId']]
        grouped_df = network_df.groupby(['attributeOrigType']).count()
        grouped_df.sort_values(by=['linkId'], inplace=True)
        if take_rows == 0:
            return grouped_df
        else:
            return grouped_df.tail(take_rows)

    output = get_output_path_from_s3_url(s3path)
    path = output + '/network.csv.gz'
    grouped_network_df = show_network(path)
    print(str(take_rows) + " max link types from network from run:     " + s3path.split('/')[-1])
    print(grouped_network_df)
    print("")


def print_file_from_url(file_url):
    file = urllib.request.urlopen(file_url)
    for b_line in file.readlines():
        print(b_line.decode("utf-8"))


def plot_hists(df, column_group_by, column_build_hist, ax, bins=100, alpha=0.2):
    for (i, d) in df.groupby(column_group_by):
        d[column_build_hist].hist(bins=bins, alpha=alpha, ax=ax, label=i)
    ax.legend()


def calc_number_of_rows_in_beamlog(s3url, keyword):
    s3path = get_output_path_from_s3_url(s3url)
    beamlog = urllib.request.urlopen(s3path + "/beamLog.out")
    count = 0
    for b_line in beamlog.readlines():
        line = b_line.decode("utf-8")
        if keyword in line:
            count = count + 1
    print("there are {} of '{}' in {}".format(count, keyword, s3path + '/beamLog.out'))
