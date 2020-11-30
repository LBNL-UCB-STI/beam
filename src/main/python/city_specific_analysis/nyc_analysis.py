"""
this is compilation of functions to analyse BEAM-related data for NYC simulation
"""

from urllib.error import HTTPError

import matplotlib.pyplot as plt
import numpy as np
import time
import datetime as dt
import pandas as pd

from shapely.geometry import Point
from shapely.geometry.polygon import Polygon
from io import StringIO


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


def read_traffic_counts(df):
    df['date'] = df['Date'].apply(lambda x: dt.datetime.strptime(x, "%m/%d/%Y"))
    df['hour_0'] = df['12:00-1:00 AM']
    df['hour_1'] = df['1:00-2:00AM']
    df['hour_2'] = df['2:00-3:00AM']
    df['hour_3'] = df['2:00-3:00AM']
    df['hour_4'] = df['3:00-4:00AM']
    df['hour_5'] = df['4:00-5:00AM']
    df['hour_6'] = df['5:00-6:00AM']
    df['hour_7'] = df['6:00-7:00AM']
    df['hour_8'] = df['7:00-8:00AM']
    df['hour_9'] = df['9:00-10:00AM']
    df['hour_10'] = df['10:00-11:00AM']
    df['hour_11'] = df['11:00-12:00PM']
    df['hour_12'] = df['12:00-1:00PM']
    df['hour_13'] = df['1:00-2:00PM']
    df['hour_14'] = df['2:00-3:00PM']
    df['hour_15'] = df['3:00-4:00PM']
    df['hour_16'] = df['4:00-5:00PM']
    df['hour_17'] = df['5:00-6:00PM']
    df['hour_18'] = df['6:00-7:00PM']
    df['hour_19'] = df['7:00-8:00PM']
    df['hour_20'] = df['8:00-9:00PM']
    df['hour_21'] = df['9:00-10:00PM']
    df['hour_22'] = df['10:00-11:00PM']
    df['hour_23'] = df['11:00-12:00AM']
    df = df.drop(['Date', '12:00-1:00 AM', '1:00-2:00AM', '2:00-3:00AM', '3:00-4:00AM', '4:00-5:00AM', '5:00-6:00AM',
                  '6:00-7:00AM', '7:00-8:00AM', '8:00-9:00AM',
                  '9:00-10:00AM', '10:00-11:00AM', '11:00-12:00PM', '12:00-1:00PM', '1:00-2:00PM', '2:00-3:00PM',
                  '3:00-4:00PM', '4:00-5:00PM', '5:00-6:00PM',
                  '6:00-7:00PM', '7:00-8:00PM', '8:00-9:00PM', '9:00-10:00PM', '10:00-11:00PM', '11:00-12:00AM'],
                 axis=1)
    return df


def aggregate_per_hour(traffic_df, date):
    wednesday_df = traffic_df[traffic_df['date'] == date]
    agg_df = wednesday_df.groupby(['date']).sum()
    agg_list = []
    for i in range(0, 24):
        xs = [i, agg_df['hour_%d' % i][0]]
        agg_list.append(xs)
    return pd.DataFrame(agg_list, columns=['hour', 'count'])


def plot_traffic_count(date):
    # https://data.cityofnewyork.us/Transportation/Traffic-Volume-Counts-2014-2018-/ertz-hr4r
    path_to_csv = 'https://data.cityofnewyork.us/api/views/ertz-hr4r/rows.csv?accessType=DOWNLOAD'
    df = read_traffic_counts(pd.read_csv(path_to_csv))
    agg_per_hour_df = aggregate_per_hour(df, date)
    agg_per_hour_df.plot(x='hour', y='count', title='Date is %s' % date)


def get_volume_reference_values():
    nyc_volumes_benchmark_date = '2018-04-11'
    nyc_volumes_benchmark_raw = read_traffic_counts(
        pd.read_csv('https://data.cityofnewyork.us/api/views/ertz-hr4r/rows.csv?accessType=DOWNLOAD'))
    nyc_volumes_benchmark = aggregate_per_hour(nyc_volumes_benchmark_raw, nyc_volumes_benchmark_date)
    return nyc_volumes_benchmark


def plot_simulation_volumes_vs_bench(s3url, iteration, ax, title="Volume SUM comparison with reference.",
                                     simulation_volumes=None, s3path=None, nyc_volumes_reference_values=None):
    if s3path is None:
        s3path = get_output_path_from_s3_url(s3url)

    if nyc_volumes_reference_values is None:
        nyc_volumes_reference_values = get_volume_reference_values()

    def calc_sum_of_link_stats(link_stats_file_path, chunksize=100000):
        start_time = time.time()
        df = pd.concat([df.groupby('hour')['volume'].sum() for df in
                        pd.read_csv(link_stats_file_path, low_memory=False, chunksize=chunksize)])
        df = df.groupby('hour').sum().to_frame(name='sum')
        # print("link stats url:", link_stats_file_path)
        print("link stats downloading and calculation took %s seconds" % (time.time() - start_time))
        return df

    if simulation_volumes is None:
        linkstats_path = s3path + "/ITERS/it.{0}/{0}.linkstats.csv.gz".format(iteration)
        simulation_volumes = calc_sum_of_link_stats(linkstats_path)

    color_reference = 'tab:red'
    color_volume = 'tab:green'

    ax1 = ax

    ax1.set_title('{} iter {}'.format(title, iteration))
    ax1.set_xlabel('hour of day')

    ax1.plot(range(0, 24), nyc_volumes_reference_values['count'], color=color_reference, label="reference")
    ax1.plot(np.nan, color=color_volume, label="simulation volume")  # to have both legends on same axis
    ax1.legend(loc="upper right")
    ax1.xaxis.set_ticks(np.arange(0, 24, 1))

    ax1.tick_params(axis='y', labelcolor=color_reference)

    volume_per_hour = simulation_volumes[0:23]['sum']
    volume_hours = list(volume_per_hour.index)

    shifted_hours = list(map(lambda x: x + 1, volume_hours))

    ax12 = ax1.twinx()  # to plot things on the same graph but with different Y axis
    ax12.plot(shifted_hours, volume_per_hour, color=color_volume)
    ax12.tick_params(axis='y', labelcolor=color_volume)

    return simulation_volumes


# index is hour
nyc_activity_ends_reference = [0.010526809, 0.007105842, 0.003006647, 0.000310397, 0.011508960, 0.039378258,
                               0.116178879, 0.300608907, 0.301269741, 0.214196234, 0.220456846, 0.237608230,
                               0.258382041, 0.277933413, 0.281891163, 0.308248524, 0.289517677, 0.333402259,
                               0.221353890, 0.140322664, 0.110115403, 0.068543370, 0.057286657, 0.011845660]


def plot_activities_ends_vs_bench(s3url, iteration, ax, ax2=None, title="Activity ends comparison.", population_size=1,
                                  activity_ends=None, s3path=None):
    if s3path is None:
        s3path = get_output_path_from_s3_url(s3url)

    def load_activity_ends(events_file_path, chunksize=100000):
        start_time = time.time()
        try:
            df = pd.concat([df[df['type'] == 'actend']
                            for df in pd.read_csv(events_file_path, low_memory=False, chunksize=chunksize)])
        except HTTPError:
            raise NameError('can not download file by url:', events_file_path)
        df['hour'] = (df['time'] / 3600).astype(int)
        print("activity ends loading took %s seconds" % (time.time() - start_time))
        return df

    if activity_ends is None:
        events_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)
        activity_ends = load_activity_ends(events_path)

    color_act_ends = 'tab:blue'

    ax.set_title('{} iter {} [{} total act ends]'.format(title, iteration, activity_ends.shape[0]))
    ax.set_xlabel('hour of day')
    ax.xaxis.set_ticks(np.arange(0, 24, 1))

    act_ends_24 = activity_ends[activity_ends['hour'] <= 24].copy()

    act_ends_total = act_ends_24.groupby('hour')['hour'].count() / population_size
    act_ends_hours = list(act_ends_total.index)

    def plot_act_ends(ax_to_plot, act_type):
        df = act_ends_24[act_ends_24['actType'] == act_type].groupby('hour')['hour'].count() / population_size
        ax_to_plot.plot(df.index, df, label='# of {} ends'.format(act_type))

    def plot_benchmark_and_legend(ax_to_plot):
        color_benchmark = 'black'
        ax_to_plot.plot(np.nan, color=color_benchmark,
                        label='benchmark (right scale)')  # to have both legends on same axis

        ax_to_plot.legend(loc="upper right")
        ax_to_plot.tick_params(axis='y', labelcolor=color_act_ends)

        ax_twinx = ax_to_plot.twinx()  # to plot things on the same graph but with different Y axis
        ax_twinx.plot(range(0, 24), nyc_activity_ends_reference, color=color_benchmark)
        ax_twinx.tick_params(axis='y', labelcolor=color_benchmark)

    ax.plot(act_ends_hours, act_ends_total, color=color_act_ends, label='# of activity ends', linewidth=3)
    plot_act_ends(ax, 'Work')
    plot_act_ends(ax, 'Home')

    plot_benchmark_and_legend(ax)

    if ax2 is not None:
        ax2.set_title('other activities')
        ax2.set_xlabel('hour of day')
        ax2.xaxis.set_ticks(np.arange(0, 24, 1))

        plot_act_ends(ax2, 'Meal')
        plot_act_ends(ax2, 'SocRec')
        plot_act_ends(ax2, 'Shopping')
        plot_act_ends(ax2, 'Other')

        plot_benchmark_and_legend(ax2)

    return activity_ends


def plot_volumes_comparison_on_axs(s3url, iteration, suptitle="", population_size=1,
                                   simulation_volumes=None, activity_ends=None,
                                   plot_simulation_volumes=True, plot_activities_ends=True):
    fig1, (ax1, ax2) = plt.subplots(1, 2, figsize=(25, 7))
    fig1.tight_layout(pad=0.1)
    fig1.subplots_adjust(wspace=0.25, hspace=0.1)
    plt.xticks(np.arange(0, 24, 2))
    plt.suptitle(suptitle, y=1.05, fontsize=17)

    if plot_simulation_volumes:
        plot_simulation_volumes_vs_bench(s3url, iteration=iteration, ax=ax1,
                                         title="Volume SUM comparison with benchmark.",
                                         simulation_volumes=simulation_volumes)

    if plot_activities_ends:
        plot_activities_ends_vs_bench(s3url, iteration=iteration, ax=ax2, title="Activity ends comparison.",
                                      population_size=population_size, activity_ends=activity_ends)


def read_nyc_ridership_counts_absolute_numbers_for_mta_comparison(s3url, iteration=0):
    holland_tunnel = {1110292, 1110293, 1110294, 1110295, 540918, 540919, 782080, 782081}
    linkoln_tunnel = {1057628, 1057629, 1057630, 1057631, 308, 309, 817812, 817813, 817814, 817815, 87180, 87181}
    george_washingtone_bridge = {735454, 735455, 767820, 767821, 781014, 781015, 781086, 781087, 781156, 781157, 782128,
                                 782129, 796856, 796857, 796858, 796859, 796870, 796871, 866324, 866325, 87174, 87175,
                                 87176, 87177, 88110, 88111, 886008, 886009, 968272, 968273, 781094, 781095}
    henry_hudson_bridge = {1681043, 1681042, 542015, 542014, 88230, 88231}
    robert_f_kennedy_bridge = {1235912, 1235913, 1247588, 1247589, 21094, 21095, 23616, 23617, 29774, 29775, 30814,
                               30815, 763932, 763933, 782436, 782437, 782438, 782439, 782440, 782441, 782560, 782561,
                               782570, 782571, 782702, 782703, 782706, 782707, 782708, 782709, 782718, 782719, 870348,
                               870349, 782720, 782721, 782722, 782723, 782724, 782725, 782726, 782727, 782728, 782729,
                               782914, 782915, 853900, 853901, 1230075, 1233314, 1233315, 1299262, 1299263, 1299264,
                               1299265, 1299266, 1299267, 1299268, 1299269, 1299274, 1299275, 1299278, 1299279, 958834,
                               958835, 958836, 958837, 916655, 1041132, 1041133, 1078046, 1078047, 1078048, 1078049,
                               1078050, 1078051, 1078052, 1078053, 1078056, 1078057, 1078058, 1078059, 1078060, 1078061,
                               1089632, 1089633, 1089634, 1089635, 1101864, 1101865, 1101866, 1101867, 1230068, 1230069,
                               1230070, 1230071, 1230072, 1230073, 1230074, 916652, 916653, 916654, 757589, 757588,
                               853929, 853928, 779898, 779899, 1339888, 1339889, 1339890, 1339891, 1433020, 1433021,
                               154, 155, 731748, 731749, 731752, 731753, 731754, 731755, 731766, 731767, 731768, 731769,
                               731770, 731771, 731786, 731787, 853892, 853893, 868400, 868401, 868410, 868411}
    queens_midtown_tunnel = {1367889, 1367888, 487778, 487779}
    hugh_l_carey_tunnel = {1071576, 1071577, 1109400, 1109401, 13722, 13723, 1658828, 1658829, 19836, 19837}
    bronx_whitestone_bridge = {62416, 62417, 729848, 729849, 765882, 765883, 853914, 853915}
    throgs_neck_bridge = {1090614, 1090615, 1090616, 1090617, 1090618, 1090619, 765880, 765881}
    varrazzano_narrows_bridge = {788119, 788118, 1341065, 1341064, 788122, 788123, 788140, 788141}
    marine_parkwaygil_hodges_memorial_bridge = {1750240, 1750241, 53416, 53417, 732358, 732359, 761184, 761185, 761186,
                                                761187, 793744, 793745}
    cross_bay_veterans_memorial_bridge = {1139186, 1139187, 1139198, 1139199, 1139200, 1139201, 1139208, 1139209,
                                          1139214, 1139215, 1139222, 1139223, 1139300, 1139301, 1139302, 1139303,
                                          1517804, 1517805, 1517806, 1517807, 1517808, 1517809, 1743514, 1743515,
                                          1749330, 1749331, 1749332, 1749333, 48132, 48133, 51618, 51619, 51620, 51621,
                                          59452, 59453, 68364, 68365, 793786, 793787, 865036, 865037, 865060, 865061,
                                          865062, 865063, 953766, 953767, 953768, 953769, 999610, 999611, 999626,
                                          999627, 999628, 999629, 1297379}

    mta_briges_tunnels_links = holland_tunnel \
        .union(linkoln_tunnel) \
        .union(george_washingtone_bridge) \
        .union(henry_hudson_bridge) \
        .union(robert_f_kennedy_bridge) \
        .union(queens_midtown_tunnel) \
        .union(hugh_l_carey_tunnel) \
        .union(bronx_whitestone_bridge) \
        .union(throgs_neck_bridge) \
        .union(varrazzano_narrows_bridge) \
        .union(marine_parkwaygil_hodges_memorial_bridge) \
        .union(cross_bay_veterans_memorial_bridge)

    s3path = get_output_path_from_s3_url(s3url)

    events_file_path = "{0}/ITERS/it.{1}/{1}.events.csv.gz".format(s3path, iteration)
    columns = ['type', 'person', 'vehicle', 'vehicleType', 'links', 'time', 'driver']
    pte = pd.concat([df[(df['type'] == 'PersonEntersVehicle') | (df['type'] == 'PathTraversal')][columns]
                     for df in pd.read_csv(events_file_path, chunksize=100000, low_memory=False)])

    print('read pev and pt events of shape:', pte.shape)

    pev = pte[(pte['type'] == 'PersonEntersVehicle')][['type', 'person', 'vehicle', 'time']]
    pte = pte[(pte['type'] == 'PathTraversal')][['type', 'vehicle', 'vehicleType', 'links', 'time', 'driver']]

    walk_transit_modes = {'BUS-DEFAULT', 'RAIL-DEFAULT', 'SUBWAY-DEFAULT'}
    drivers = set(pte[pte['vehicleType'].isin(walk_transit_modes)]['driver'])
    pev = pev[~pev['person'].isin(drivers)]

    def get_gtfs_agency(row):
        veh_id = row['vehicle'].split(":")
        if len(veh_id) > 1:
            agency = veh_id[0]
            return agency
        return ""

    def car_by_mta_bridges_tunnels(row):
        if pd.isnull(row['links']):
            return False

        for link_str in row['links'].split(","):
            link = int(link_str)
            if link in mta_briges_tunnels_links:
                return True

        return False

    pte['carMtaRelated'] = pte.apply(car_by_mta_bridges_tunnels, axis=1)
    pte['gtfsAgency'] = pte.apply(get_gtfs_agency, axis=1)

    vehicle_info = pte.groupby('vehicle')[['vehicleType', 'gtfsAgency']].first().reset_index()

    pev_advanced = pd.merge(pev, vehicle_info, on='vehicle')
    pev_advanced = pev_advanced.sort_values('time', ignore_index=True)

    gtfs_agency_to_count = pev_advanced.groupby('gtfsAgency')['person'].count()

    # calculate car
    car_mode = {'Car', 'Car-rh-only', 'PHEV', 'BUS-DEFAULT'}
    car_mta_related = pte[(pte['vehicleType'].isin(car_mode)) &
                          (pte['carMtaRelated'])]['time'].count()
    transit_car_to_count = gtfs_agency_to_count.append(pd.Series([car_mta_related], index=['Car']))

    # calculate subway
    person_pevs = pev_advanced.groupby('person').agg(list)[['vehicleType', 'gtfsAgency']]

    def calc_number_of_subway_trips(row):
        vehicle_list = row['vehicleType']
        count_of_trips = 0
        last_was_subway = False
        for vehicle in vehicle_list:
            if vehicle == 'SUBWAY-DEFAULT':
                if not last_was_subway:
                    count_of_trips = count_of_trips + 1
                    last_was_subway = True
            else:
                last_was_subway = False
        return count_of_trips

    person_pevs['subway_trips'] = person_pevs.apply(calc_number_of_subway_trips, axis=1)
    subway_trips = person_pevs['subway_trips'].sum()

    triptype_to_count = transit_car_to_count.append(pd.Series([subway_trips], index=['Subway']))
    triptype_to_count = triptype_to_count.to_frame().reset_index()

    print('calculated:\n', pev_advanced.groupby('vehicleType')['person'].count())

    return triptype_to_count


def calculate_nyc_ridership_and_save_to_s3_if_not_calculated(s3url, iteration, aws_access_key_id, aws_secret_access_key,
                                                             force=False, output_bucket='beam-outputs'):
    if force:
        print('"force" set to True, so, ridership will be recalculated independent of it existence in s3')
    else:
        print('"force" set to False (by default) so, ridership will be calculated only if it does not exist in s3')

    import boto3
    s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id, aws_secret_access_key=aws_secret_access_key)

    s3_additional_output = 'scripts_output'

    ridership = None

    require_string = 'index.html#'
    if require_string not in s3url:
        print(
            's3url does not contain "{}". That means there is no way to save result of the function. Calculation '
            'cancelled.'.format(
                require_string))
    else:
        ridership_file_name = '{}.nyc_mta_ridership.csv.gz'.format(iteration)
        folder_path = s3url.split('#')[1].strip()

        s3path = get_output_path_from_s3_url(s3url)
        path = "{}/{}/{}".format(s3path, s3_additional_output, ridership_file_name)

        def calculate():
            print("Ridership calculation...")
            ridership_df = read_nyc_ridership_counts_absolute_numbers_for_mta_comparison(s3url, iteration)
            ridership_df.to_csv(ridership_file_name)
            out_path = "{}/{}/{}".format(folder_path, s3_additional_output, ridership_file_name)
            s3.meta.client.upload_file(ridership_file_name, output_bucket, out_path)
            print('\nuploaded\nto: backet {}, path {}\n\n'.format(output_bucket, out_path))
            return ridership_df

        if force:
            ridership = calculate()
        else:
            try:
                ridership = pd.read_csv(path, low_memory=False)
                print("file exist with path '{}'".format(path))
            except HTTPError:
                print("Looks like file does not exits with path '{}'".format(path))
                ridership = calculate()

    return ridership


def calculate_ridership_and_fake_walkers_for_s3urls(s3urls, iteration, aws_access_key_id, aws_secret_access_key):
    for s3url in s3urls:
        print(s3url)
        ridership = calculate_nyc_ridership_and_save_to_s3_if_not_calculated(s3url, iteration, aws_access_key_id,
                                                                             aws_secret_access_key)
        print('ridership done\n')

    for s3url in s3urls:
        print(s3url)

        fake_walkers_file_name = "{}.fake_real_walkers.csv.gz".format(iteration)
        walkers = get_from_s3(s3url, fake_walkers_file_name)
        if walkers is None:
            walkers = get_fake_real_walkers(s3url, iteration)
            save_to_s3(s3url, walkers, fake_walkers_file_name, aws_access_key_id, aws_secret_access_key)
        else:
            print('file {} already exist for url {}'.format(fake_walkers_file_name, s3url))
        print(walkers)


def read_nyc_gtfs_trip_id_to_route_id():
    base_path = "https://beam-outputs.s3.us-east-2.amazonaws.com/new_city/newyork/gtfs_trips_only_per_agency/"
    files = ['MTA_Bronx_20200121_trips.csv.gz', 'MTA_Brooklyn_20200118_trips.csv.gz',
             'MTA_Manhattan_20200123_trips.csv.gz', 'MTA_Queens_20200118_trips.csv.gz',
             'MTA_Staten_Island_20200118_trips.csv.gz', 'NJ_Transit_Bus_20200210_trips.csv.gz']

    urls = map(lambda file_name: base_path + file_name, files)
    trip_id_to_route_id = {}

    for url in urls:
        trips = pd.read_csv(url.strip(), low_memory=False)[['route_id', 'trip_id']]
        for index, row in trips.iterrows():
            trip_id_to_route_id[str(row['trip_id'])] = row['route_id']
        print(len(trip_id_to_route_id))

    return trip_id_to_route_id


def read_bus_ridership_by_route_and_hour(s3url, gtfs_trip_id_to_route_id=None, iteration=0):
    if not gtfs_trip_id_to_route_id:
        gtfs_trip_id_to_route_id = read_nyc_gtfs_trip_id_to_route_id()

    s3path = get_output_path_from_s3_url(s3url)

    events_file_path = "{0}/ITERS/it.{1}/{1}.events.csv.gz".format(s3path, iteration)
    columns = ['type', 'person', 'vehicle', 'vehicleType', 'time', 'driver']
    pte = pd.concat([df[(df['type'] == 'PersonEntersVehicle') | (df['type'] == 'PathTraversal')][columns]
                     for df in pd.read_csv(events_file_path, chunksize=100000, low_memory=False)])

    print('read PEV and PT events of shape:', pte.shape)

    pev = pte[(pte['type'] == 'PersonEntersVehicle')][['person', 'vehicle', 'time']]
    pev['hour'] = pev['time'] // 3600

    pte = pte[(pte['type'] == 'PathTraversal') & (pte['vehicleType'] == 'BUS-DEFAULT')]
    drivers = set(pte['driver'])

    pev = pev[~pev['person'].isin(drivers)]

    print('got PEV {} and PT {}'.format(pev.shape, pte.shape))

    def get_gtfs_agency_trip_id_route_id(row):
        agency = ""
        trip_id = ""
        route_id = ""

        veh_id = row['vehicle'].split(":")
        if len(veh_id) > 1:
            agency = veh_id[0]
            trip_id = str(veh_id[1])
            route_id = gtfs_trip_id_to_route_id.get(trip_id, "")

        return [agency, trip_id, route_id]

    pte[['gtfsAgency', 'gtfsTripId', 'gtfsRouteId']] = pte \
        .apply(get_gtfs_agency_trip_id_route_id, axis=1, result_type="expand")

    print('calculated gtfs agency, tripId and routeId')

    columns = ['vehicleType', 'gtfsAgency', 'gtfsTripId', 'gtfsRouteId']
    vehicle_info = pte.groupby('vehicle')[columns].first().reset_index()

    pev = pd.merge(pev, vehicle_info, on='vehicle')

    print('got advanced version of PEV:', pev.shape, 'with columns:', pev.columns)

    walk_transit_modes = {'BUS-DEFAULT'}  # ,'RAIL-DEFAULT', 'SUBWAY-DEFAULT'
    bus_to_agency_to_trip_to_hour = pev[(pev['vehicleType'].isin(walk_transit_modes))] \
        .groupby(['gtfsAgency', 'gtfsRouteId', 'hour'])['person'].count()

    return bus_to_agency_to_trip_to_hour


def plot_nyc_ridership(s3url_to_ridership, function_get_run_name_from_s3url, names_to_plot_separately=None, multiplier=20, figsize=(20, 7)):
    columns = ['date', 'subway', 'bus', 'rail', 'car', 'transit (bus + subway)']

    suffix = '\n  mta.info'
    reference_mta_info = [['09 2020' + suffix, 1489413, 992200, 130600, 810144, 2481613],
                          ['08 2020' + suffix, 1348202, 1305000, 94900, 847330, 2653202],
                          ['07 2020' + suffix, 1120537, 1102200, 96500, 779409, 2222737],
                          ['06 2020' + suffix, 681714, 741200, 56000, 582624, 1422914],
                          ['05 2020' + suffix, 509871, 538800, 29200, 444179, 1048671],
                          ['04 2020' + suffix, 516174, 495400, 24100, 342222, 1011574],
                          [' 2019' + suffix, 5491213, 2153913, 622000, 929951, 7645126]]

    def get_graph_data_row_from_dataframe(triptype_to_count_df, run_name, agency_column='index', value_column='0'):

        def get_agency_data(agency):
            return triptype_to_count_df[triptype_to_count_df[agency_column] == agency][value_column].values[0]

        def get_sum_agency_data(agencies):
            agencies_sum = 0
            for agency in agencies:
                agencies_sum = agencies_sum + get_agency_data(agency)
            return agencies_sum

        mta_bus = get_sum_agency_data(['MTA_Bronx_20200121', 'MTA_Brooklyn_20200118',
                                       'MTA_Manhattan_20200123', 'MTA_Queens_20200118',
                                       'MTA_Staten_Island_20200118'])

        mta_rail = get_sum_agency_data(['Long_Island_Rail_20200215',
                                        'Metro-North_Railroad_20200215'])

        mta_subway = get_agency_data('Subway')
        car = get_agency_data('Car')
        transit = mta_subway + mta_bus

        return [run_name,
                mta_subway * multiplier,
                mta_bus * multiplier,
                mta_rail * multiplier,
                car * multiplier,
                transit * multiplier]

    graph_data = []

    for s3url, triptype_to_count in s3url_to_ridership.items():
        title = function_get_run_name_from_s3url(s3url)
        row = get_graph_data_row_from_dataframe(triptype_to_count, title)
        graph_data.append(row)

    result = pd.DataFrame(graph_data, columns=columns)
    reference_df = pd.DataFrame(reference_mta_info, columns=columns)
    result = result.append(reference_df).groupby('date').sum()

    def plot_bars(df, ax, ax_title, columns_to_plot):
        df[columns_to_plot].plot(kind='bar', ax=ax)
        # ax.grid('on', which='major', axis='y')
        ax.set_title(ax_title)
        ax.legend(loc='center left', bbox_to_anchor=(1, 0.7))

    fig, axs = plt.subplots(1, 1, sharex='all', figsize=figsize)
    ax_main = axs

    plot_bars(result, ax_main,
              'reference from mta.info vs BEAM simulation\nrun data multiplied by {}'.format(multiplier),
              ['subway', 'bus', 'rail', 'car', 'transit (bus + subway)'])

    if names_to_plot_separately:
        def plot_bars_2(df, ax, ax_title, columns_to_plot):
            df[columns_to_plot].plot(kind='bar', ax=ax)
            ax.set_title(ax_title)
            # ax.legend(loc='upper right')
            ax.legend(loc='center left', bbox_to_anchor=(1, 0.7))

        result_t = result[['subway', 'bus', 'rail', 'car']].transpose()

        fig, axs = plt.subplots(1, len(names_to_plot_separately), sharey='all', figsize=figsize)
        fig.subplots_adjust(wspace=0.3, hspace=0.3)

        if len(names_to_plot_separately) == 1:
            axs = [axs]

        for (name, ax) in zip(names_to_plot_separately, axs):
            selected_columns = []
            for column in result_t.columns:
                if str(column).startswith(name):
                    selected_columns.append(column)

            plot_bars_2(result_t, ax, "", selected_columns)

        plt.suptitle('reference from mta.info vs BEAM simulation\nrun data multiplied by {}'.format(20))

def read_ridership_from_s3_output(s3url, iteration):
    ridership = None
    s3_additional_output = 'scripts_output'

    require_string = 'index.html#'
    if require_string not in s3url:
        print(
            's3url does not contain "{}". That means there is no way read prepared output.'.format(require_string))
    else:
        ridership_file_name = '{}.nyc_mta_ridership.csv.gz'.format(iteration)
        s3path = get_output_path_from_s3_url(s3url)
        path = "{}/{}/{}".format(s3path, s3_additional_output, ridership_file_name)

        try:
            ridership = pd.read_csv(path, low_memory=False)
            print("downloaded ridership from ", path)
        except HTTPError:
            print("Looks like file does not exits -> '{}'".format(path))

    return ridership


def compare_riderships_vs_baserun_and_benchmark(title_to_s3url, iteration, s3url_base_run, date_to_calc_diff=None,
                                                figsize=(20, 5), rot=15, suptitle="",
                                                plot_columns=None, plot_reference=True):
    columns = ['date', 'subway', 'bus', 'rail', 'car', 'transit']

    suffix = '\n  mta.info'

    benchmark_mta_info = [['09 2020' + suffix, -72.90, -54.00, -78.86, -12.90, -68.42],
                          ['08 2020' + suffix, -75.50, -40.00, -83.32, -08.90, -66.68],
                          ['07 2020' + suffix, -79.60, -49.00, -83.91, -16.20, -71.90],
                          ['06 2020' + suffix, -87.60, -66.00, -90.95, -37.40, -82.17],
                          ['05 2020' + suffix, -90.70, -75.00, -95.00, -52.30, -86.89],
                          ['04 2020' + suffix, -90.60, -77.00, -96.13, -63.20, -87.47]]

    if not plot_columns:
        plot_columns = columns[1:]

    date_to_benchmark = {}
    for row in benchmark_mta_info:
        date_to_benchmark[row[0]] = row[1:]

    print('reference dates:', date_to_benchmark.keys())

    def column_name_to_passenger_multiplier(column_name):
        if column_name == '0':
            return 1

        delimeter = '-'
        if delimeter in column_name:
            nums = column_name.split(delimeter)
            return (int(nums[0]) + int(nums[1])) // 2
        else:
            return int(column_name)

    def get_sum_of_passenger_per_trip(df, ignore_hour_0=True):
        sum_df = df.sum()
        total_sum = 0

        for column in df.columns:
            if column == 'hours':
                continue
            if ignore_hour_0 and column == '0':
                continue
            multiplier = column_name_to_passenger_multiplier(column)
            total_sum = total_sum + sum_df[column] * multiplier

        return total_sum

    def get_car_bus_subway_trips(beam_s3url):
        s3path = get_output_path_from_s3_url(beam_s3url)

        def read_csv(filename):
            file_url = s3path + "/ITERS/it.{0}/{0}.{1}.csv".format(iteration, filename)
            try:
                return pd.read_csv(file_url)
            except HTTPError:
                print('was not able to download', file_url)

        sub_trips = read_csv('passengerPerTripSubway')
        bus_trips = read_csv('passengerPerTripBus')
        car_trips = read_csv('passengerPerTripCar')
        rail_trips = read_csv('passengerPerTripRail')

        sub_trips_sum = get_sum_of_passenger_per_trip(sub_trips, ignore_hour_0=True)
        bus_trips_sum = get_sum_of_passenger_per_trip(bus_trips, ignore_hour_0=True)
        car_trips_sum = get_sum_of_passenger_per_trip(car_trips, ignore_hour_0=False)
        rail_trips_sum = get_sum_of_passenger_per_trip(rail_trips, ignore_hour_0=True)

        return car_trips_sum, bus_trips_sum, sub_trips_sum, rail_trips_sum

    (base_car, base_bus, base_sub, base_rail) = get_car_bus_subway_trips(s3url_base_run)

    graph_data = []

    for (run_title, s3url_run) in title_to_s3url:
        (minus_car, minus_bus, minus_sub, minus_rail) = get_car_bus_subway_trips(s3url_run)

        def calc_diff(base_run_val, minus_run_val):
            return (minus_run_val - base_run_val) / base_run_val * 100

        diff_transit = calc_diff(base_sub + base_bus + base_rail, minus_sub + minus_bus + minus_rail)
        diff_sub = calc_diff(base_sub, minus_sub)
        diff_bus = calc_diff(base_bus, minus_bus)
        diff_car = calc_diff(base_car, minus_car)
        diff_rail = calc_diff(base_rail, minus_rail)

        graph_data.append(['{0}'.format(run_title), diff_sub, diff_bus, diff_rail, diff_car, diff_transit])

    def plot_bars(df, ax, title, columns_to_plot):
        df.groupby('date').sum()[columns_to_plot].plot(kind='bar', ax=ax, rot=rot)
        ax.grid('on', which='major', axis='y')
        ax.set_title(title)
        ax.legend(loc='center left', bbox_to_anchor=(1, 0.7))

    if date_to_calc_diff:
        fig, axs = plt.subplots(1, 2, sharey='all', figsize=figsize)
        ax_main = axs[0]
    else:
        fig, axs = plt.subplots(1, 1, sharey='all', figsize=figsize)
        ax_main = axs

    fig.tight_layout(pad=0.1)
    fig.subplots_adjust(wspace=0.25, hspace=0.1)

    plt.suptitle('Comparison of difference vs baseline and vs real data from MTI.info\n{}'.format(suptitle), y=1.2,
                 fontsize=17)

    result = pd.DataFrame(graph_data, columns=columns)
    if plot_reference:
        reference_df = pd.DataFrame(benchmark_mta_info, columns=columns)
        result = result.append(reference_df)

    plot_bars(result, ax_main, 'reference from mta.info vs BEAM simulation', plot_columns)

    if date_to_calc_diff:
        df_to_compare = pd.DataFrame(graph_data, columns=columns)
        diff = df_to_compare[columns[1:]].sub(date_to_benchmark[date_to_calc_diff + suffix], axis=1)
        diff[columns[0]] = df_to_compare[columns[0]]
        plot_bars(diff, axs[1], 'runs minus reference at {}'.format(date_to_calc_diff), plot_columns)


def people_flow_in_cbd_s3(s3url, iteration):
    s3path = get_output_path_from_s3_url(s3url)
    events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)
    return people_flow_in_cbd_file_path(events_file_path)


def people_flow_in_cbd_file_path(events_file_path, chunksize=100000):
    events = pd.concat([events[events['type'] == 'PathTraversal'] for events in
                        pd.read_csv(events_file_path, low_memory=False, chunksize=chunksize)])
    return people_flow_in_cdb(events)


def diff_people_flow_in_cbd_s3(s3url, iteration, s3url_base, iteration_base):
    s3path = get_output_path_from_s3_url(s3url)
    events_file_path = s3path + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration)
    s3path_base = get_output_path_from_s3_url(s3url_base)
    events_file_path_base = s3path_base + "/ITERS/it.{0}/{0}.events.csv.gz".format(iteration_base)
    return diff_people_flow_in_cbd_file_path(events_file_path, events_file_path_base)


def diff_people_flow_in_cbd_file_path(events_file_path, events_file_path_base, chunksize=100000):
    events = pd.concat([events[events['type'] == 'PathTraversal'] for events in
                        pd.read_csv(events_file_path, low_memory=False, chunksize=chunksize)])
    events_base = pd.concat([events[events['type'] == 'PathTraversal'] for events in
                             pd.read_csv(events_file_path_base, low_memory=False, chunksize=chunksize)])
    return diff_people_in(events, events_base)


def people_flow_in_cdb(df):
    polygon = Polygon([
        (-74.005088, 40.779100),
        (-74.034957, 40.680314),
        (-73.968867, 40.717604),
        (-73.957924, 40.759091)
    ])

    def inside(x, y):
        point = Point(x, y)
        return polygon.contains(point)

    def num_people(row):
        mode = row['mode']
        if mode in ['walk', 'bike']:
            return 1
        elif mode == 'car':
            return 1 + row['numPassengers']
        else:
            return row['numPassengers']

    def benchmark():
        data = """mode,Entering,Leaving
subway,2241712,2241712
car,877978,877978
bus,279735,279735
rail,338449,338449
ferry,66932,66932
bike,33634,33634
tram,3528,3528
        """
        return pd.read_csv(StringIO(data)).set_index('mode')

    f = df[(df['type'] == 'PathTraversal')][['mode', 'numPassengers', 'startX', 'startY', 'endX', 'endY']].copy(
        deep=True)

    f['numPeople'] = f.apply(lambda row: num_people(row), axis=1)
    f = f[f['numPeople'] > 0]

    f['startIn'] = f.apply(lambda row: inside(row['startX'], row['startY']), axis=1)
    f['endIn'] = f.apply(lambda row: inside(row['endX'], row['endY']), axis=1)
    f['numIn'] = f.apply(lambda row: row['numPeople'] if not row['startIn'] and row['endIn'] else 0, axis=1)

    s = f.groupby('mode')[['numIn']].sum()
    b = benchmark()

    t = pd.concat([s, b], axis=1)
    t.fillna(0, inplace=True)

    t['percentIn'] = t['numIn'] * 100 / t['numIn'].sum()
    t['percent_ref'] = t['Entering'] * 100 / t['Entering'].sum()

    t = t[['numIn', 'Entering', 'percentIn', 'percent_ref']]

    t['diff'] = t['percentIn'] - t['percent_ref']
    t['diff'].plot(kind='bar', title="Diff: current - reference, %", figsize=(7, 5), legend=False, fontsize=12)

    t.loc["Total"] = t.sum()
    return t


def get_people_in(df):
    polygon = Polygon([
        (-74.005088, 40.779100),
        (-74.034957, 40.680314),
        (-73.968867, 40.717604),
        (-73.957924, 40.759091)
    ])

    def inside(x, y):
        point = Point(x, y)
        return polygon.contains(point)

    def num_people(row):
        mode = row['mode']
        if mode in ['walk', 'bike']:
            return 1
        elif mode == 'car':
            return 1 + row['numPassengers']
        else:
            return row['numPassengers']

    f = df[(df['type'] == 'PathTraversal') & (df['mode'].isin(['car', 'bus', 'subway']))][
        ['mode', 'numPassengers', 'startX', 'startY', 'endX', 'endY']].copy(deep=True)

    f['numPeople'] = f.apply(lambda row: num_people(row), axis=1)
    f = f[f['numPeople'] > 0]

    f['startIn'] = f.apply(lambda row: inside(row['startX'], row['startY']), axis=1)
    f['endIn'] = f.apply(lambda row: inside(row['endX'], row['endY']), axis=1)
    f['numIn'] = f.apply(lambda row: row['numPeople'] if not row['startIn'] and row['endIn'] else 0, axis=1)

    s = f.groupby('mode')[['numIn']].sum()

    s.fillna(0, inplace=True)

    s['percentIn'] = s['numIn'] * 100 / s['numIn'].sum()

    return s['percentIn']


def diff_people_in(current, base):
    def reference():
        data = """date,subway,bus,car
07/05/2020,-77.8,-35,-21.8
06/05/2020,-87.2,-64,-30.8
05/05/2020,-90.5,-73,-50.3
04/05/2020,-90.5,-71,-78.9
03/05/2020,0.0,4,-0.1
        """
        ref = pd.read_csv(StringIO(data), parse_dates=['date'])
        ref.sort_values('date', inplace=True)
        ref['month'] = ref['date'].dt.month_name()
        ref = ref.set_index('month').drop('date', 1)
        return ref

    b = get_people_in(base)
    c = get_people_in(current)
    b.name = 'base'
    c.name = 'current'

    t = pd.concat([b, c], axis=1)
    t['increase'] = t['current'] - t['base']

    pc = reference()

    run = t['increase'].to_frame().T
    run = run.reset_index().drop('index', 1)
    run['month'] = 'Run'
    run = run.set_index('month')
    result = pd.concat([run, pc], axis=0)

    result.plot(kind='bar', title="Diff current - reference, %", figsize=(10, 10), legend=True, fontsize=12)
    return result


def plot_calibration_parameters(title_to_s3url,
                                suptitle="", figsize=(23, 6), rot=70,
                                calibration_parameters=None,
                                removal_probabilities=None):
    if calibration_parameters is None:
        calibration_parameters = ['additional_trip_utility', 'walk_transit_intercept']

    calibration_values = []

    for (title, s3url) in title_to_s3url:
        s3path = get_output_path_from_s3_url(s3url)
        config = parse_config(s3path + "/fullBeamConfig.conf", complain=False)

        def get_config_value(conf_value_name, split_character="="):
            return config.get(conf_value_name, '=default').split(split_character)[-1]

        param_values = [title]
        for param in calibration_parameters:
            param_value = get_config_value(param)
            if not param_value:
                param_value = get_config_value(param, ":")
            if not param_value:
                param_value = 0

            param_values.append(float(param_value))

        calibration_values.append(param_values)

    calibration_parameters.insert(0, 'name')
    result = pd.DataFrame(calibration_values, columns=calibration_parameters)

    linewidth = 4
    removal_probabilities_color = 'black'

    ax = result.plot(x='name', figsize=figsize, rot=rot, linewidth=linewidth)

    if removal_probabilities:
        ax.plot(np.NaN, np.NaN, '--', label='removal probabilities (right scale)',
                color=removal_probabilities_color, linewidth=linewidth)

    ax.set_title('calibration parameters {}'.format(suptitle))
    ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left')

    ax.grid('on', which='major', axis='y')

    if removal_probabilities:
        ax2 = ax.twinx()
        ax2.plot(range(len(removal_probabilities)), removal_probabilities, '--',
                 color=removal_probabilities_color, alpha=0.5, linewidth=linewidth)