from copy import deepcopy
from collections import defaultdict
# import csv
import datetime
import gc
import os
import re
import time
from xml.dom import minidom
import xml.etree.ElementTree as ET

import fiona
import numpy as np
import pandas as pd
import pyproj as pp
import rtree
from shapely.geometry import Polygon, Point, LineString
#import shapefile  # (Andrew 14/09/16)


__author__ = 'Andrew A Campbell'


######################
# Matching the Links #
######################

def match_links_from_metadata(meta_path, shape_path, EPSG, radius, x_col='Longitude', y_col='Latitude',
                station_col='ID', output_file='Matched_censors.csv', link_regex="."):
    """
    Wrapper for the old match_links that reads the station locations from a PeMS metadata file that possibly contains
    duplicate station_ID, latitude, longitude 3-tuples.
    :param meta_path: (str) Path to the PeMS metadata file that contains sensor locations. May have duplicate
    station_ID, latitude, longitude 3-tuples.
    :param shape_path: (str) Path to shapefile of network
    :param EPSG: (str) Numberical EPSG code defining the???
    :param radius: (float) Search radius around a sensor for finding candidate sensors to match to it.
    :param x_col: (str) Name of column in data_df with x-coordinate values. Default value is 'Longitude'.
    :param y_col: (str) Name of column in data_df with y-coordinate values. Default value is 'Latitude'.
    :param station_col: (str) Name of column with sensor station id.
    :param output_file: (str) Path to ouput csv file. Defaults to writing 'Matched_censors.csv' in the working
    directory.
    :param link_regex (str) Regex pattern to identify links we want to include in matching. Used to exclude public
    transit links.
    :return: (list) Writes a csv containing the map of sensors to links. Returns a list of sensors with no coordinate
    match.
    """
    data_df = pd.read_csv(meta_path)[[station_col, x_col, y_col]].drop_duplicates()
    return match_links(data_df, shape_path, EPSG, radius, x_col, y_col, station_col, output_file, link_regex=link_regex)

def match_links(data_df, shape_path, EPSG, radius, x_col='Longitude', y_col='Latitude',
                station_col="station", output_file='Matched_censors.csv', link_regex="."):
    """
    Matches each sensor to a link in the network.
    Code adapted from: https://github.com/neverforgit/UCB_Mobility_Simulation/blob/master/1.%20Code/Y.%20Clustering/Functions_2.py
    :param data_df: (DataFrame) Pandas DataFrame containing the sensor locations.
    :param shape_path: (str) Path to shapefile of network
    :param EPSG: (str) Numberical EPSG code defining the???
    :param radius: (float) Search radius around a sensor for finding candidate sensors to match to it.
    :param x_col: (str) Name of column in data_df with x-coordinate values. Default value is 'Longitude'.
    :param y_col: (str) Name of column in data_df with y-coordinate values. Default value is 'Latitude'.
    :param station_col: (str) Name of column with sensor station id.
    :param output_file: (str) Path to ouput csv file. Defaults to writing 'Matched_censors.csv' in the working
    directory.
    :param link_regex (str) Regex pattern to identify links we want to include in matching. Used to exclude public
    transit links.
    :return: (list) Writes a csv containing the map of sensors to links. Returns a list of sensors with no coordinate
    match.
    """

    t00 = time.time()
    idx = rtree.index.Index()

    ##
    # Loading Sensors
    ##

    convert = pp.Proj(init="epsg:" + EPSG)  # 32611 for LA

    ##
    # Preparing Sensors into a list
    ##

    t0 = time.time()
    troubled = data_df[pd.isnull(data_df[y_col])]
    data_df.dropna(inplace=True)
    data_df.reset_index(inplace=True)
    #TODO delete the following line if it runs with it commented outWriter.
    #data_df.drop('index', axis=1, inplace=True)
    data_df['x'], data_df['y'], data_df['Link'], data_df['ds'] = '', '', 0.0, 0.0
    data_df[['x', 'y']] = map(convert, data_df[x_col], data_df[y_col])
    t = time.time() - t0
    print 'time: ', t, '\n'
    print 'Sensors Ready...\n'

    ##
    # Loading the Links
    ##

    # Create a ID dicts. That way we can accept Link_IDs that are non-numeric
    link_to_num = defaultdict(int)  # maps original link_ids to integers
    num_to_link = dict()  # maps those integers back to original link_ids

    t0 = time.time()
    input_lines = fiona.open(shape_path)
    pattern = re.compile(link_regex)  # for filtering outWriter public transit links

    for i, row in enumerate(input_lines):
        print i, ' Now processing ...', row['properties']['ID']
        link_id = row['properties']['ID']
        # Skip the link if its id does not match the link_regex pattern
        if not pattern.match(link_id):
            continue
        temp = [row['geometry']['coordinates'][0], row['geometry']['coordinates'][1], row['geometry']['coordinates'][2]]
        left = min(temp[0][0], temp[1][0], temp[2][0])
        bot = min(temp[0][1], temp[1][1], temp[2][1])
        right = max(temp[0][0], temp[1][0], temp[2][0])
        top = max(temp[0][1], temp[1][1], temp[2][1])
        link_to_num[link_id] += 1
        num_to_link[link_to_num[row['properties']['ID']]] = link_id
        # idx.insert(int(row['properties']['ID']), coordinates=(left, bot, right, top),
        #            obj=[row['properties']['ID'], row['geometry']])
        idx.insert(link_to_num[link_id], coordinates=(left, bot, right, top),
                   obj=[row['properties']['ID'], row['geometry']])

    t = time.time() - t0
    print 'time: ', t, '\n'
    print 'Lines Ready... \n'

    t0 = time.time()

    for i in range(len(data_df)):
        print 'Now Matching Sensor: ', data_df.loc[i, station_col], '\n'
        p = Point(data_df.loc[i, 'x'], data_df.loc[i, 'y'])
        temp = list(idx.nearest((p.coords.xy[0][0], p.coords.xy[1][0]), num_results=5,
                                objects='raw'))  # num_results 5 because apparently rtree approaches outside in, if num_result==1 then it will be 142 rows short

        shortest = {}
        for entry in temp:
            line = LineString(entry[1]['coordinates'])
            d = ('%.2f' % p.distance(line))
            shortest[d] = entry[0]
        ds = ('%.2f' % min(float(e) for e in shortest))  # shortest distance of points
        link = shortest[str(ds)]  #
        if float(ds) <= radius:
            data_df.loc[i, ['Link', 'ds']] = link, float(ds)

    matched = data_df[data_df['ds'] != 0.0]
    duplicate_links = list(set(matched[matched.duplicated('Link') == True]['Link'].values))

    print 'Cleaning the duplicate links...'
    for link in duplicate_links:
        temp = data_df[data_df['Link'] == link]
        grouped = temp.groupby('ds')  # groupe the distance ONLY in the duplicated sensors
        if len(grouped) == 1:  # if there is only one group it means it is the same sensor, different loop detectors
            pass
        else:
            m = ('%.2f' % temp['ds'].min())  # find the minimum distance in the instances
            drop_id = temp[temp['ds'] > float(m)][station_col]  # put their ID's in drop_id
            for i in range(len(drop_id.values)):  # iterate through the drop_id's
                cleaned_df = data_df.drop(data_df[data_df[station_col] == drop_id.values[
                    i]].index)  # drop the rows who have the ID in the drop_ids and put the cleaned data in cleaned_data_df

    d = 0
    if duplicate_links != []:
        matched_cleaned = cleaned_df[cleaned_df['ds'] != 0.0]  # only links with matched sensors
        matched_cleaned = matched_cleaned.sort_values('Link')
        d = 1  # a switch for aesthetic purposes while pritning the progress

    print 'Cleaned data ready...'
    print 'Matching done, Preparing for CSV...'

    t0 = time.time()

    if d == 1:
        matched_cleaned['Link'] = [lk for lk in matched_cleaned['Link']] # convert to int
        matched_cleaned.to_csv(output_file, columns=[station_col, 'Link', 'ds', y_col, x_col], index=False)
    else:
        matched['Link'] = [lk for lk in matched['Link']] # convert to int
        matched.to_csv(output_file, columns=[station_col, 'Link', 'ds', y_col, x_col], index=False)
    t = time.time() - t0
    print 'time: ', t, '\n'
    print 'CSV Ready!'

    print 'Overall Time: ', time.time() - t00

    print 'Done'
    return troubled

##################################################
# Creating counts files  from MTC data
##################################################

def create_MTC_counts(mtc_df, matched_df, flow_type, template_file, counts_name, counts_desc, counts_year, out_file='counts.xml'):
    """
    Creates a counts.xml file for MATSim to validate simulated counts against based on the MTC's processed PeMS files.
    :param mtc_df: (DataFrame) Pandas DataFrame of the raw csv provided by MTC at
    https://mtcdrive.app.box.com/share-data
    :param matched_df: (DataFrame) Pandas DataFrame containing the mapping of stations to links in the network. This is
    a DataFrame of the ouput of mtc_PeMS_tools.match_links. NOTE: the station id must be used as the index
    :param flow_type: (str) Flags whether to create a counts file of mean or median hourly values. Acceptable values are
    "mean" or "median".
    :param template_file: (str) Path to template of counts.xml output.
    :param counts_name: (str) Name of counts data set to be used in root attribute of counts.xml
    :param counts_desc: (str) Very short description to be used in the root attribute of counts.xml
    :param counts_year: (int) Year of counts data to be used in root attributes of counts.xml
    :param out_file: (str) Path and name of output counts file. Defaults to counts.xml
    :return: ([DataFrame, DataFrame]) Returns the ouput of the call to count_ts. The first DataFrame is the desired
    time series output. The second is a DataFrame of stations that were excluded and the reason why.
    """
    # Create filtere time series for each station
    ts_df, filter_df = counts_ts(mtc_df, matched_df, flow_type)

    # Initialize the ElementTree using the template. Update the attributes for the root, <counts>
    tree = ET.parse(template_file)  # weekday count file
    root = tree.getroot()
    root.attrib['name'] = counts_name
    root.attrib['desc'] = counts_desc
    root.attrib['year'] = str(counts_year)
    count0 = root.getchildren()[0]

    # Add placeholder <count> blocks for all rows in ts_df
    for row in np.arange(ts_df.shape[0] - 1):
        root.append(deepcopy(count0))

    # Iterate through each sensor in ts_df and update the values of a new <count> block
    for i, row in enumerate(ts_df.iterrows()):
        ci = root.getchildren()[i]  # i'th count block
        # Update the attributes for <count>
        ci.attrib['loc_id'] = str(int(matched_df.loc[row[0]].loc['Link']))  # Add the link id
        ci.attrib['cs_id'] = str(row[0])  # Add the sensor station id
        # Iterate to update the volume vals
        for j, volume in enumerate(ci.getchildren()):
            volume.attrib['val'] = str(row[1][j])

    # Write the xml to the output file
    tree.write(out_file)
    return ts_df, filter_df

def get_TS_summary_dates(summary_path, strpfrmt='%m/%d/%Y'):
    """
    :param summary_path: (str) Path to Time Series summary
    :param strpfrmt: (str) Format string for reading dates
    :returns (datetime, datetime)
    """
    df = pd.read_csv(summary_path)
    start = datetime.datetime.strptime(df['First_Day'][0], strpfrmt)
    end = datetime.datetime.strptime(df['Last_Day'][0], strpfrmt)
    return (start, end)


def counts_ts(mtc_df, matched_df, flow_type, stat_id='station'):
    """
    Takes a DataFrame of the raw csv provided by MTC. Returns an N x 25 data frame where the first column is the station
    id number and the other 24 are the hourly counts.
    :param mtc_df: (DataFrame) Pandas DataFrame of the raw csv provided by MTC at
    https://mtcdrive.app.box.com/share-data
    :param matched_df: (DataFrame) Pandas DataFrame containing the mapping of stations to links in the network. This is
    a DataFrame of the ouput of mtc_PeMS_tools.match_links
    :param flow_type: (str) Flags whether to create a counts file of mean or median hourly values. Acceptable values are
    "avg_flow" or "median_flow".
    :param stat_id: (str) Name of column with the station ids.
    :return: ([DataFrame, DataFrame]) The first DataFrame is the desired time series output. The second is a DataFrame of
    stations that were excluded and the reason why.
    """

    stations = pd.unique(mtc_df[stat_id])  # array of unique station ids
    columns = [str(x) for x in np.arange(0, 24)]
    ts_df = pd.DataFrame(index=stations, columns=columns)  # empty placeholder DataFrame
    filter_dict = {}
    for stat in stations:
        # Check for missing values and for a link match
        match = stat in matched_df[stat_id].values
        stat_ts = mtc_df[mtc_df[stat_id] == stat][['hour', flow_type]].sort('hour')
        if not match:
            filter_dict[stat] = 'No match'
            ts_df.drop(stat, inplace=True)
            continue
        elif stat_ts.shape[0] != 24:
            ts_df.drop(stat, inplace=True)
            filter_dict[stat] = 'Missing values'
            continue
        else:
            ts_df.loc[stat] = stat_ts[flow_type].values
    # Convert filter_dict to a DataFrame and return
    filter_df = pd.DataFrame.from_dict(filter_dict, orient='index')
    filter_df.columns = ['reason']
    return ts_df, filter_df

##################################################
# Filtering Counts Sensor Stations
##################################################



##################################################
# Creating counts files from PeMS_Tools data
##################################################

#TODO verify that we can simply use create_PeMS_Tools_counts_multiday to do single day counts, then delete the single day version below and rename this one to something more general (AAC Spring 16)
#TODO remove the filtering by missing data. Add a qualified Station ID list as input

def create_PeMS_Tools_counts_multiday(station_TS_dir, stat_link_map_file, date_list, counts_name, counts_desc, counts_year, filter_list, aggregation='mean', _list=None, out_file='counts', printerval=100):
    """
    :param station_TS_dir: (str) Path to directory with station Time Series. This must be the output of a call to
    PeMS_Tools.utilities.station.generate_time_series_V2.
    :param stat_link_map_file: (str) CSV file mapping station_ID to MATSim Link_ID.
    :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
    :param counts_name: (str) Name of counts data set to be used in root attribute of counts.xml
    :param counts_desc: (str) Very short description to be used in the root attribute of counts.xml
    :param counts_year: (int) Year of counts data to be used in root attributes of counts.xml
    :param filter_list: ([str]) If not None, only PeMS station IDs in this list will be used.
    :param aggregation: ([str]) List of Pandas methods of aggregation  to use(mean, median or std only acceptable values).
    :param out_file: (str) Path and name of output counts file. Defaults to counts.xml
    :return: ([DataFrame, DataFrame]) Returns the ouput of the call to count_ts. The first DataFrame is the desired
    time series output. The second is a DataFrame of stations that were excluded and the reason why.
    """

    # Initialize the ElementTree
    tree = create_template_tree()
    root = tree.getroot()
    root.attrib['name'] = counts_name
    root.attrib['desc'] = counts_desc
    root.attrib['year'] = str(counts_year)
    count0 = deepcopy(root.getchildren()[0])
    root.remove(list(root)[0])  # remove the one dummy count element

    # Get the station_ID to link_ID lookup
    id_map = pd.read_csv(stat_link_map_file, index_col='ID', dtype='string')
    id_map.index = [str(i) for i in id_map.index]  # convert index back to string for easy lookups below


    # Move through all the time series directories and add the sensor if data available.
    orig_dir = os.getcwd()
    os.chdir(station_TS_dir)
    stations = [n for n in os.listdir('.') if n.isdigit()]  # list of all station folder names
    used_links = []
    if filter_list:
        stations = [stat for stat in stations if stat in filter_list]

    for i, stat in enumerate(stations):
        if i % printerval == 0:
            print 'Processing station: %s (%s of %s)' % (stat, i, len(stations))
        if id_map.loc[stat]['Link'] in used_links:
            print '%s has a duplicate link id, skipping' % stat
            continue
        start, end = get_TS_summary_dates('./%s/summary.csv' % stat)
        if stat not in id_map.index:  # Check if the station was successfully mapped to a link
            continue
        if not((start < datetime.datetime.strptime(date_list[0], '%m/%d/%Y')) & (datetime.datetime.strptime(date_list[-1], '%m/%d/%Y')< end)):  # Check if this station was active during target periods
            continue # Skip this station
        df = pd.read_csv('./%s/time_series.csv' % stat, index_col='Timestamp')
        df['date'] = [d[0:10] for d in df.index]
        df['hour'] = [d[-8:-6] for d in df.index]

        # Make a small df of only days matching the target day date. It is much much faster to resample the smaller one
        vol_5min = df[df['date'].isin(date_list)][['Total_Flow', 'hour']] # all 5-min observations on desired dates
        vol_5min.index = pd.to_datetime(vol_5min.index)
        vol_hourly = vol_5min.resample('1h', how='sum')  # Rollup the 5-minute counts to 1-hr
        vol_hourly['hour'] = [d.hour for d in vol_hourly.index]
        # Now we need to groupby the hour and take the mean
        hr_means = vol_hourly.groupby('hour').mean()  # Mean hourly flows for all days in date_list!!!
        #TODO perhaps we should run this filter earlier. Imagine we have one hour with one observation on one day and the sensor is off for all others.
        if hr_means['Total_Flow'].isnull().any():  # skip if any hours are missing
            continue
        # Add the counts to the ElementTree
        link_id = id_map.loc[stat]['Link']
        used_links.append(link_id)
        augment_counts_tree(tree, hr_means['Total_Flow'].values, stat, link_id, count0)
        if i%100:  # garbage collect every 100 iterations
            gc.collect()
    # tree.write(out_file, encoding='UTF-8')
    pretty_xml = prettify(tree.getroot())
    with open(out_file, 'w') as fo:
        fo.write(pretty_xml)
    os.chdir(orig_dir)

def create_PeMS_Tools_counts_measures(station_TS_dir, stat_link_map_file, date_list, counts_name, counts_desc, counts_year, filter_list, aggregation_list, _list=None, out_prefix='counts'):
    """
    :param station_TS_dir: (str) Path to directory with station Time Series. This must be the output of a call to
    PeMS_Tools.utilities.station.generate_time_series_V2.
    :param stat_link_map_file: (str) CSV file mapping station_ID to MATSim Link_ID.
    :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
    :param counts_name: (str) Name of counts data set to be used in root attribute of counts.xml
    :param counts_desc: (str) Very short description to be used in the root attribute of counts.xml
    :param counts_year: (int) Year of counts data to be used in root attributes of counts.xml
    :param filter_list: ([str]) If not None, only PeMS station IDs in this list will be used.
    :param aggregation_list: ([str]) List of Pandas methods of aggregation  to use(mean, median or std only acceptable values).
    :param out_prefix: (str) Path and name of output counts file. Defaults to counts.xml
    :return: ([DataFrame, DataFrame]) Returns the ouput of the call to count_ts. The first DataFrame is the desired
    time series output. The second is a DataFrame of stations that were excluded and the reason why.
    """

    # Create a list of trees, one for each aggregation measure
    tree_list = []
    for agg in aggregation_list:
        # Initialize the ElementTree
        tree = create_template_tree()
        root = tree.getroot()
        root.attrib['name'] = agg + " - " + counts_name
        root.attrib['desc'] = agg + " - " + counts_desc
        root.attrib['year'] = str(counts_year)
        count0 = deepcopy(root.getchildren()[0])
        root.remove(list(root)[0])  # remove the one dummy count element
        tree_list.append(tree)

    # Get the station_ID to link_ID lookup
    id_map = pd.read_csv(stat_link_map_file, index_col='ID', dtype='string')
    id_map.index = [str(i) for i in id_map.index]  # convert index back to string for easy lookups below

    # Move through all the time series directories and add the sensor if data available.
    orig_dir = os.getcwd()
    os.chdir(station_TS_dir)
    stations = [n for n in os.listdir('.') if n.isdigit()]  # list of all station folder names
    if filter_list:
        stations = [stat for stat in stations if stat in filter_list]
    for i, stat in enumerate(stations):
        print 'Processing station: %s' % stat
        start, end = get_TS_summary_dates('./%s/summary.csv' % stat)
        if stat not in id_map.index:  # Check if the station was successfully mapped to a link
            continue
        if not((start < datetime.datetime.strptime(date_list[0], '%m/%d/%Y')) & (datetime.datetime.strptime(date_list[-1], '%m/%d/%Y')< end)):  # Check if this station was active during target periods
            continue # Skip this station
        df = pd.read_csv('./%s/time_series.csv' % stat, index_col='Timestamp')
        df['date'] = [d[0:10] for d in df.index]
        df['hour'] = [d[-8:-6] for d in df.index]

        # Make a small df of only days matching the target day date. It is much much faster to resample the smaller one
        vol_5min = df[df['date'].isin(date_list)][['Total_Flow', 'hour']] # all 5-min observations on desired dates
        vol_5min.index = pd.to_datetime(vol_5min.index)
        vol_hourly = vol_5min.resample('1h', how='sum')  # Rollup the 5-minute counts to 1-hr
        vol_hourly['hour'] = [d.hour for d in vol_hourly.index]

        # Now we need to groupby the hour and take the measures
        for i, agg in enumerate(aggregation_list):
            # 1 - generate the aggregation
            if agg == 'mean':
                hr_agg = vol_hourly.groupby('hour').mean()  # Mean hourly flows for all days in date_list!!!
            elif agg == 'median':
                hr_agg = vol_hourly.groupby('hour').median()  # Median hourly flows for all days in date_list!!!
            elif agg == 'std':
                hr_agg = vol_hourly.groupby('hour').std()  # Standard dev of hourly flows for all days in date_list!!!
            #TODO perhaps we should run this filter earlier. Imagine we have one hour with one observation on one day and the sensor is off for all others.
            if hr_agg['Total_Flow'].isnull().any():  # skip if any hours are missing
                continue
            # Add the counts to the ElementTree
            tree = tree_list[i]
            link_id = id_map.loc[stat]['Link']
            augment_counts_tree(tree, hr_agg['Total_Flow'].values, stat, link_id, count0)
        if i%100:  # garbage collect every 100 iterations
            gc.collect()
    for i, tree in enumerate(tree_list):
        # tree.write(out_file, encoding='UTF-8')
        pretty_xml = prettify(tree.getroot())
        with open(out_prefix + "_" + aggregation_list[i] + ".txt", 'w') as fo:
            fo.write(pretty_xml)
    os.chdir(orig_dir)


##################################################
# XML ET tools
##################################################
#TODO all this xml stuff should go into its own class: class CountTree(ElementTree)
def augment_counts_tree(counts_tree, data, station_ID, link_ID, empty_count):
    """
    Add data for a new detector station (sensor) to an existing XML tree.
    :param counts_tree: (xml.etree.ElementTree) Tree of MATSim counts.
    :param data: (list-like) Contains 24 elements. Each is the hourly mean or median flow.
    :param station_ID: (str) ID of the physical detector station.
    :param link_ID: (str) ID of the MATSim network link that the station maps to.
    :param empty_count: (xml.etree.ElementTree.Element)
    """
    new_count = deepcopy(empty_count)
    # Add the IDs and volumes to the new 'count' element
    new_count.attrib['loc_id'] = link_ID
    new_count.attrib['cs_id'] = station_ID
    for i, vol in enumerate(list(new_count)):
        vol.attrib['val'] = str(int(data[i]))
    counts_tree.getroot().append(new_count)  # add to the tree
    return counts_tree

def create_template_tree():
    """
    Creates an empty counts ElementTree.
    """
    root = ET.Element('counts')
    # root.set('version', '1.0')
    # root.set('encoding', 'UTF-8')
    root_atts = vd = {'xmlns:xsi': "http://www.w3.org/2001/XMLSchema-instance",
                      'xsi:noNamespaceSchemaLocation': "http://matsim.org/files/dtd/counts_v1.xsd",
                      'name': "name of the dataset",
                      'desc': "describe the dataset",
                      'year': "yyyy",
                      'layer': "0"}
    root.attrib = root_atts
    ET.SubElement(root, 'count')
    ct = list(root)[0]
    for hr in np.arange(1,25):
        vol = ET.Element('volume', {'h': str(hr), 'val': ''})
        ct.append(vol)
    return ET.ElementTree(root)

def df_from_counts(counts_path):
    """
    :param counts_path: (str) Path to a MATSim counts.xml file
    :returns (DataFrame) Rows are hours (0:24) and cols are sensor stations.
    """
    tree = ET.parse(counts_path)
    counts = tree.getroot()
    n_stats = len(counts.getchildren())  # number of unique sensor stations
    stat_dfs = {}
    for count in counts.getchildren():
        stat_dfs[count.attrib['cs_id']] = [int(v.attrib['val']) for v in count.getchildren()] # each count element is a unique station
    return pd.DataFrame(stat_dfs)

##################################################
# Validation tools
##################################################

def validate_screenline(link_ids, aggr_hours, counts_df, counts_col, facility_name):
    """

    :param link_ids: ([int]) List of the IDs of the MATSim network links.
    :param aggr_hours: ([int]) Defines the hours to be included in aggregation. Uses 24-hr time. First hour is 1, last
    is 24.
    :param counts_df: (pd.DataFrame) DataFrame of the MATSim counts validation output. DF has not been processed
    significantly after running pd.read_csv.
    :param counts_col: (str) Name of the column of counts to aggregate. This is useful if you have added a rescaled
    column.
    :param facility_name: (str) Name of screenline facility
    :return: ([int, int, int, double])  Screenline Facility, Observed, Predicted, Predicted_Less_Obs, Prcnt_Difference
    """
    rows = counts_df[np.array([id in link_ids for id in counts_df['Link Id']]) &
                     np.array([count in aggr_hours for count in counts_df['Hour']])]
    observed = np.sum(rows['Count volumes'])
    predicted = np.sum(rows[counts_col])
    diff = predicted - observed
    try:
        prct_diff = np.true_divide(diff, observed)
    except ZeroDivisionError:
        prct_diff = float("inf")
    return [facility_name, observed, predicted, diff, prct_diff]

def optimize_counts(df):
    """
    Used for optimizing the CountsScaleFactor parameter that MATSim uses for scaling counts.
    :param df: (pd.DataFrame) A DF read directly from the run0.*.countscompare.txt file that MATSim
    produces.
    :return: (float, float, float) The optimized value to scale the CountsScaleFactor by, original RMSE, and new RMSE
    after applying the optimized CountsScaleFactor
    """
    # Calculate original RMSE
    o_numer = np.dot(df['MATSIM volumes'].subtract(df['Count volumes']), df['MATSIM volumes'].subtract(df['Count volumes']))
    o_denom = df.shape[0]
    o_RMSE = np.sqrt(np.true_divide(o_numer, o_denom))

    # Optimal value to rescale CountsScaleFactor
    alpha = np.true_divide(df['MATSIM volumes'].dot(df['Count volumes']), df['MATSIM volumes'].dot(df['MATSIM volumes']))

    # Rescaled RMSE
    r_numer = np.dot(alpha * df['MATSIM volumes'] - df['Count volumes'],
                     alpha * df['MATSIM volumes'] - df['Count volumes'])
    r_RMSE = np.sqrt(np.true_divide(r_numer, o_denom))

    return alpha, o_RMSE, r_RMSE

##
# Helpers
##

def prettify(elem):
    """Return a pretty-printed XML string for the Element.
    Curtosy of: https://pymotw.com/2/xml/etree/ElementTree/create.html
    """
    rough_string = ET.tostring(elem, 'utf-8')
    reparsed = minidom.parseString(rough_string)
    return reparsed.toprettyxml(indent="  ")

def date_string_list(start_date, end_date, weekdays, dt_format='%m/%d/%Y'):
    """
    :param start_date: (datetime.datetime) First date in range
    :param end_date: (datetime.datetime) Last date in range
    :param weekdays: ([int]) Days of week to use (Monday = 0)
    :param dt_format: (str) Date format string. Conforms to the strftime grammar.
    :returns ([str]) List of date strings. Default is '%m/%d/%Y'
    """
    all_dates = pd.date_range(start_date, end_date)
    date_list = []
    for d in all_dates:
        if d.weekday() in weekdays:
            date_list.append(datetime.datetime.strftime(d, dt_format))
    return date_list


def get_datetime_range(start_date, end_date, time_delta):
    """
    :param start_date: (datetime.datetime) First date (included)
    :param end_date: (datetime.datetime) Last date (not included)
    :param time_delta: (datetime.timedelta) Increment
    :returns ([datetime.datetime]) All datetimes from start_date up to, but not including, end_date
    """
    dt = start_date
    out = []
    while dt < end_date:
        out.append(dt)
        dt += time_delta
    return out

def get_24_index():
    """
    Returns a list of 24 datetime.datetimes that are at 1hr intervals. Has year and day values because the datetime
    it has to.
    """
    start = datetime.datetime(1999, 12, 31, 0)
    end = datetime.datetime(2000, 01, 01, 0)
    td = datetime.timedelta(0, 3600)
    return get_datetime_range(start + td, end + td, td)







