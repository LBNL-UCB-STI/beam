import analyze_events_v2
import pandas as pd
import os
import ssl
import urllib.request
from pandas.io.json import json_normalize
import json

# Helpers


def download_events(__url, __output_file_path, __iteration):
    downloaded_file = '{}.events.csv.gz'.format(__output_file_path)
    if not os.path.exists(downloaded_file):
        if not os.path.exists(__output_file_path.rsplit("/", 1)[0]):
            os.makedirs(__output_file_path.rsplit("/", 1)[0])
        if (not os.environ.get('PYTHONHTTPSVERIFY', '') and
                getattr(ssl, '_create_unverified_context', None)):
            ssl._create_default_https_context = ssl._create_unverified_context
        url = "{0}/ITERS/it.{1}/{1}.events.csv.gz".format(__url, __iteration)
        import datetime
        print("[{}] downloading {} ...".format(datetime.datetime.now(), url))
        urllib.request.urlretrieve(url, downloaded_file)
        print("[{}] donwload ok".format(datetime.datetime.now()))
    return downloaded_file


def download_stats(__url, __output_file_path):
    downloaded_file = '{}.summaryStats.csv'.format(__output_file_path)
    if not os.path.exists(downloaded_file):
        if not os.path.exists(__output_file_path.rsplit("/", 1)[0]):
            os.makedirs(__output_file_path.rsplit("/", 1)[0])
        if (not os.environ.get('PYTHONHTTPSVERIFY', '') and
                getattr(ssl, '_create_unverified_context', None)):
            ssl._create_default_https_context = ssl._create_unverified_context
        url = "{}/summaryStats.csv".format(__url)
        print("downloading {} ...".format(url))
        urllib.request.urlretrieve(url, downloaded_file)
    return downloaded_file


def download_beamLog(__url, __output_file_path):
    downloaded_file = '{}.beamLog.out'.format(__output_file_path)
    if not os.path.exists(downloaded_file):
        if not os.path.exists(__output_file_path.rsplit("/", 1)[0]):
            os.makedirs(__output_file_path.rsplit("/", 1)[0])
        if (not os.environ.get('PYTHONHTTPSVERIFY', '') and
                getattr(ssl, '_create_unverified_context', None)):
            ssl._create_default_https_context = ssl._create_unverified_context
        url = "{}/beamLog.out".format(__url)
        print("downloading {} ...".format(url))
        urllib.request.urlretrieve(url, downloaded_file)
    return downloaded_file


def get_metrics_from_stats(__url, __output_file_path, __iteration):
    summary_stats_file = download_stats(__url, __output_file_path)
    summary_stats_df = pd.read_csv(summary_stats_file, sep=",", index_col=None, header=0)
    return summary_stats_df[summary_stats_df['Iteration'] == __iteration]


def get_metrics_from_events(__url, __output_file_path, __iteration):
    metrics_json = analyze_events_v2.get_all_metrics(download_events(__url, __output_file_path, __iteration),__output_file_path)
    with open("{}.all-metrics.json".format(__output_file_path), 'w') as outfile:
        json.dump(metrics_json, outfile)
    return pd.DataFrame.from_dict(json_normalize(metrics_json))


def get_metrics_from_beamLog(__url, __output_file_path):
    with open(download_beamLog(__url, __output_file_path)) as beamLog:
        for line in beamLog:
            if 'Number of person' in line and 'Removed' in line:
                return pd.DataFrame([(int(line.split(':')[1].split('.')[0].strip()))], columns=['population'])
    return pd.DataFrame(columns=['population'])


def get_metrics(__setup, __output_dir):
    __index = ['Rank', 'Year', 'Scenario', 'Technology', 'Iteration']
    final_output_df = pd.DataFrame()
    for (rank, year, iteration, scenario, technology, scenario_tech, remote_folder) in __setup['scenarios']:
        output_file_path = "{}/{}-{}".format(__output_dir, scenario_tech, year)
        output_file_path_itr = "{}-{}".format(output_file_path, iteration)
        local_metrics_file = "{}.metrics.csv".format(output_file_path_itr)
        if not os.path.exists(local_metrics_file):
            url = remote_folder

            summary_stats_df = get_metrics_from_stats(url, output_file_path, iteration)
            summary_stats_df['Scenario'] = scenario
            summary_stats_df['Technology'] = technology
            summary_stats_df['Year'] = year
            summary_stats_df['Rank'] = rank
            summary_stats_df.set_index(__index)

            pool_metrics_df = get_metrics_from_events(url, output_file_path_itr, iteration)
            pool_metrics_df['Scenario'] = scenario
            pool_metrics_df['Technology'] = technology
            pool_metrics_df['Iteration'] = iteration
            pool_metrics_df['Year'] = year
            pool_metrics_df['Rank'] = rank
            pool_metrics_df.set_index(__index)

            beamLog_df = get_metrics_from_beamLog(url, output_file_path)
            beamLog_df['Scenario'] = scenario
            beamLog_df['Technology'] = technology
            beamLog_df['Iteration'] = iteration
            beamLog_df['Year'] = year
            beamLog_df['Rank'] = rank
            beamLog_df.set_index(__index)

            merged_metrics_df = pd.merge(pool_metrics_df, summary_stats_df, on=__index, how='inner')
            merged_metrics_df = pd.merge(merged_metrics_df, beamLog_df, on=__index, how='inner')

            # writing
            merged_metrics_df.set_index(__index).to_csv(local_metrics_file)

            # concat
            final_output_df = pd.concat([final_output_df, merged_metrics_df])
        else:
            final_output_df = pd.concat([final_output_df,
                                         pd.read_csv(local_metrics_file, sep=",", index_col=None, header=0)])
        print("{} ok!".format(remote_folder))
    return final_output_df


def make_plots(__setup_config_dict):
    output_dir = __setup_config_dict['home_dir'] + "/" + __setup_config_dict['run_name']
    # years = list(set(x[1] for x in __setup_config_dict['scenarios']))
    # iterations = list(set(x[2] for x in __setup_config_dict['scenarios']))
    years_iterations = list(set((x[1], x[2]) for x in __setup_config_dict['scenarios']))
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    if not os.path.exists(output_dir + '/sankey'):
        os.makedirs(output_dir + '/sankey')

    for (year, iteration) in years_iterations:
        local_metrics_file = "{}/{}.{}.metrics-final.csv".format(output_dir, year, iteration)
        if not os.path.exists(local_metrics_file):
            filter_config = __setup_config_dict.copy()
            filter_config['scenarios'] = list(filter(lambda x: x[1] == year and x[2] == iteration, filter_config['scenarios']))
            final_output_df = get_metrics(filter_config, output_dir)
            final_output_df.sort_values(by=['Rank']).to_csv(local_metrics_file)
        #os.system("python3 makeplots_simplified.py {} {}/makeplots/{}".format(local_metrics_file, output_dir, year))
