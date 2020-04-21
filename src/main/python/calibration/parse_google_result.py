#!/usr/bin/env python3

from datetime import datetime, timedelta
import os
import numpy as np
from numpy.random import rand

import time
import traceback

import re
import math

import pandas as pd
pd.set_option('display.precision',12)

# please, use https://regex101.com/ if something is not clear

def first_attempt(travel_time_str):
     # for a case like `typically 8 - 22 min`
    regex = r"typically\s([\d]{1,2})\s-\s([\d]{1,2})\smin$"
    matches = re.finditer(regex, travel_time_str)
    enum = list(enumerate(matches, start=1))
    if len(enum) == 1:
        n, match = enum[0]
        min_time = int(match.group(1)) * 60
        max_time = int(match.group(2)) * 60
        return (min_time, max_time)
    else:
        return None

def second_attempt(travel_time_str):
    def ifNoneThenEmptyString(value):
        if value is None:
            return ""
        else:
            return value

    def getTotalTimeInMinutes(minutes, hours):
        time = 0
        if hours != '':
            time = time + int(hours) * 60
        if minutes != '':
            time = time + int(minutes)
        return time

    # typically 1 h - 1 h 30 min
    # typically 1 h 50 min - 2 h 20 min
    # typically 24 min - 1 h
    # typically 26 min - 1 h
    # typically 28 min - 1 h
    # typically 30 min - 1 h
    # typically 30 min - 1 h 5 min
    # typically 35 min - 1 h
    # typically 35 min - 1 h 5 min
    # typically 40 min - 1 h
    # typically 40 min - 1 h 5 min
    regex = r"typically\s([\d]{1,2}\sh\s){0,1}([\d]{1,2}\smin\s){0,1}-\s([\d]{1,2}\sh[\s]{0,1})([\d]{1,2}\smin){0,1}$"
    matches = re.finditer(regex, travel_time_str)
    enum = list(enumerate(matches, start=1))
    if len(enum) == 1:
        n, match = enum[0]
        # group 1 - is the hour for lower bound
        min_h = ifNoneThenEmptyString(match.group(1)).replace('h', '')
        min_m = ifNoneThenEmptyString(match.group(2)).replace('min', '')
        max_h = ifNoneThenEmptyString(match.group(3)).replace('h', '')
        max_m = ifNoneThenEmptyString(match.group(4)).replace('min', '')
        min_time = getTotalTimeInMinutes(min_m, min_h) * 60
        max_time = getTotalTimeInMinutes(max_m, max_h) * 60
        return (min_time, max_time)
    else:
        return None

def third_attempt(travel_time_str):
     # for a case like `typically 8min`
    regex = r"typically\s([\d]{1,2})\smin$"
    matches = re.finditer(regex, travel_time_str)
    enum = list(enumerate(matches, start=1))
    if len(enum) == 1:
        n, match = enum[0]
        min_time = int(match.group(1)) * 60
        max_time = int(match.group(1)) * 60
        return (min_time, max_time)
    else:
        return None

def parse_travel_time(travel_time_str):
    if isinstance(travel_time_str,float) and math.isnan(travel_time_str):
        return (float('nan'), float('nan'))
    temp = first_attempt(travel_time_str)
    if temp is not None:
        return temp
    temp = second_attempt(travel_time_str)
    if temp is not None:
        return temp
    temp = third_attempt(travel_time_str)
    if temp is not None:
        return temp
    else:
        raise Exception("Cannot parse '%s' as travel time" % ((travel_time_str)))

def parse_travel_distance(travel_distance):
    if isinstance(travel_distance,float) and math.isnan(travel_distance):
        return float('nan')
    elif "km" in travel_distance:
        return float(travel_distance.replace('km', '')) * 1000
    elif "miles" in travel_distance:
        # Miles to meteres
        return float(travel_distance.replace('miles', '')) * 1.60934 * 1000
    elif "mile" in travel_distance:
        # Miles to meteres
        return float(travel_distance.replace('mile', '')) * 1.60934 * 1000
    elif "ft" in travel_distance:
        # Miles to meteres
        return float(travel_distance.replace('ft', '')) * 0.3048
    elif "m" in travel_distance:
        return float(travel_distance.replace('m', ''))
    else:
        raise Exception("Cannot parse '%s' as travel distance" % ((travel_distance)))

def normalize(df):
    df['route_0_travel_distance_meters'] = df['route_0_travel_distance'].apply(lambda x: parse_travel_distance(x))
    df['route_1_travel_distance_meters'] = df['route_1_travel_distance'].apply(lambda x: parse_travel_distance(x))
    df['route_2_travel_distance_meters'] = df['route_2_travel_distance'].apply(lambda x: parse_travel_distance(x))

    df['route_0_travel_time_min'] = df['route_0_travel_time'].apply(lambda x: parse_travel_time(x)[0])
    df['route_0_travel_time_max'] = df['route_0_travel_time'].apply(lambda x: parse_travel_time(x)[1])
    df['route_1_travel_time_min'] = df['route_1_travel_time'].apply(lambda x: parse_travel_time(x)[0])
    df['route_1_travel_time_max'] = df['route_1_travel_time'].apply(lambda x: parse_travel_time(x)[1])
    df['route_2_travel_time_min'] = df['route_2_travel_time'].apply(lambda x: parse_travel_time(x)[0])
    df['route_2_travel_time_max'] = df['route_2_travel_time'].apply(lambda x: parse_travel_time(x)[1])

    return df

def get_input_file(hour):
  minSeconds = hour * 3600
  maxSeconds = (hour + 1) * 3600 - 1
  path = "https://beam-outputs.s3.us-east-2.amazonaws.com/analysis/austin/google_input/10.studyarea.CarRideStats.personal.output_%d_%d.csv" % (minSeconds, maxSeconds)
  return path

def get_output_file(hour):
  minSeconds = hour * 3600
  maxSeconds = (hour + 1) * 3600 - 1
  path = "https://beam-outputs.s3.us-east-2.amazonaws.com/analysis/austin/google_output/10.studyarea.CarRideStats.personal.output_%d_%d_links.txt_result.txt" % (minSeconds, maxSeconds)
  return path

def get_normalize_google(df):
  df['google_travel_time'] = (df['route_0_travel_time'].combine_first(df['route_1_travel_time'])).combine_first(df['route_2_travel_time'])
  df['google_travel_distance'] = (df['route_0_travel_distance'].combine_first(df['route_1_travel_distance'])).combine_first(df['route_2_travel_distance'])

  df['google_travel_distance_meters'] = df['google_travel_distance'].apply(lambda x: parse_travel_distance(x))
  df['google_travel_time_min'] = df['google_travel_time'].apply(lambda x: parse_travel_time(x)[0])
  df['google_travel_time_max'] = df['google_travel_time'].apply(lambda x: parse_travel_time(x)[1])
  normalized_df = normalize(df)
  filtered_df = normalized_df[normalized_df['google_travel_time'].notna() & normalized_df['google_travel_distance'].notna()]
  n_filtered = len(normalized_df) - len(filtered_df)
  if (n_filtered > 0):
    print("Filtered %d rows" % (n_filtered))
  return filtered_df

def get_input_df(hour):
  return pd.read_csv(get_input_file(hour))

def get_google_df(hour):
  columns = ['url','index','route_0_name','route_0_travel_time','route_0_travel_distance', \
             'route_1_name','route_1_travel_time','route_1_travel_distance','route_2_name','route_2_travel_time','route_2_travel_distance']
  return pd.read_csv(get_output_file(hour), usecols = columns, index_col=False)

def original_with_google_route(original_df, google_df):
    original_df['index'] = original_df.index
    join_list = ['index']
    merged_df = pd.merge(original_df, google_df, how='inner', left_on=join_list, right_on = join_list)
    return merged_df

if __name__ == "__main__":
    df_list = []
    for hour in range(0, 24):
      input_df = get_input_df(hour)
      google_df = get_google_df(hour)
      normalized_google_df = get_normalize_google(google_df)
      merged = original_with_google_route(input_df, normalized_google_df)
      print("Read data for hour %d hour. input_df size: %d, google_df: %d, merged: %d" % (hour, len(input_df), len(google_df), len(merged)))
      df_list.append(merged)

    final_df = pd.concat(df_list, ignore_index=True)
    final_df.to_csv('austin_24hours.csv', index=False)