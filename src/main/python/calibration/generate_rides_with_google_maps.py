#!/usr/bin/env python3
"""Generate A CSV file containing Google Maps links and apply filters from the Beam files: {iteration}.personal.CarRideStats.csv.gz
Example of usage:
python generate_rides_with_google_maps.py \
--inputFile="/folder-path/it.0/0.CarRideStats.csv.gz" \
--sampleSize=5 \
--sampleSeed=12345 \
--travelDistanceIntervalInMeters=[4450,6000] \
--departureTimeIntervalInSeconds=[25246,25248] \
--areaBoundBox=[(10,11.1),(12,14)]

Only inputFile is a mandatory parameter. All others are optional
"""

import getopt
import glob
import random
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

import pandas as pd
from tabulate import tabulate
import datetime

__author__ = "Carlos Caldas"

def main(args):
    program_arguments = parse_parameters(args)
    input_file_location = search_argument("--inputFile", program_arguments)
    output_file_path = generate_output_file_path(input_file_location)
    convert_file(input_file_location, output_file_path, program_arguments)


def search_argument(argument_name, arguments):
    the_tuple = filter(lambda x: x[0] == argument_name, arguments)
    result = next(the_tuple, None)
    if result is None:
        return None
    else:
        return result[1]


def parse_parameters(args):
    short_options = ""
    long_options = ["help",
                    "inputFile=",
                    "sampleSize=",
                    "sampleSeed=",
                    "travelDistanceIntervalInMeters=",
                    "departureTimeIntervalInSeconds=",
                    "areaBoundBox="]
    try:
        arguments, _ = getopt.getopt(args[1:], short_options, long_options)

        if search_argument("--inputFile", arguments) is None:
            arguments.insert(0, ("--inputFile", select_input(args[0])))

        if search_argument("--sampleSize", arguments) is not None \
                and search_argument("--sampleSeed", arguments) is None:
            seed = random.randint(0, 4294967296)
            print(f"[INFO] Seed not specified. Using the number [{seed}] as seed.")
            arguments.append(("--sampleSeed", str(seed)))

        return arguments
    except getopt.error as err:
        print(str(err))
        sys.exit(2)


def generate_output_file_path(input_file_arg):
    result_file_name = extract_name(input_file_arg)
    if is_url(input_file_arg):
        url = urlparse(input_file_arg)
        parent_path = Path(url.path[1:]).parent
        return parent_path.joinpath(result_file_name).resolve()
    else:
        absolute_path = Path(input_file_arg)
        return absolute_path.parent.joinpath(result_file_name).resolve()


def extract_name(input_file_arg):
    input_file_path = Path(input_file_arg)
    input_name = input_file_path.name
    tmp_list = input_name.rsplit(".")[:-2]
    tmp_list.append("output")
    tmp_list.append("csv")
    return ".".join(tmp_list)


def is_url(input_file_arg):
    return urlparse(input_file_arg).scheme != ''


def google_link(row, timeEpochSeconds):
    if not len(row):
        return None
    s = f"https://www.google.com/maps/dir/{row.start_y}%09{row.start_x}/{row.end_y}%09{row.end_x}"
    return s + f'/data=!3m1!4b1!4m15!4m14!1m3!2m2!1d-97.7584165!2d30.3694661!1m3!2m2!1d-97.7295503!2d30.329807!2m4!2b1!6e0!7e2!8j{timeEpochSeconds}!3e0'

def convert_file(input_file_location, output_file_path, program_arguments):
    df = read_csv_as_dataframe(input_file_location, program_arguments)

    departure_time_range = search_argument("--departureTimeIntervalInSeconds", program_arguments)
    departure_time_range = departure_time_range[1:-1]
    departure_time_start = int(departure_time_range.split(",")[0])
    departure_time_end = int(departure_time_range.split(",")[1])
    # We have chosen 2019-10-16 (Wednesday) as a day to get the routes from Google. It is 1571184000 in Unix time
    start_of_the_day = 1571184000
    google_departure_time = start_of_the_day + int((departure_time_end + departure_time_start) / 2)
    df['google_link'] = df.apply(lambda x: google_link(x, google_departure_time), axis=1)
    print(tabulate(df.head(), headers='keys', tablefmt='psql'))
    print(df.info())
    output_file_path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(output_file_path, header=True, index=False)
    google_departure_time_datetime = datetime.datetime.utcfromtimestamp(google_departure_time)
    print(f"Departure time in Google: {google_departure_time_datetime:%Y-%m-%d %H:%M:%S}")
    print(f"File [{output_file_path.absolute()}] created.")

def select_input(execution_file):
    test_output_folder = find_output_test_directory(execution_file)
    return select_file(test_output_folder)


def select_file(test_output_folder):
    found_files = list(glob.iglob(f'{test_output_folder}/**/*.personal.CarRideStats.csv.gz', recursive=True))
    if len(found_files) == 0:
        return None

    print(f"[{len(found_files)}] file(s) was/were found. Select one you want to use:")
    for idx, val in enumerate(found_files):
        print("[", idx, "]", val)
    try:
        selected_index = int(input("Type index: "))
        return found_files[selected_index]
    except ValueError:
        print("You did not entered a valid integer")
        sys.exit(1)
    except IndexError:
        print(f"You did not entered a valid integer in the range [0, {len(found_files)}]")


def find_output_test_directory(execution_file):
    all_parents_as_path = list(Path(execution_file).parents)
    all_parents_as_str = list(map(lambda x: str(x.absolute()), all_parents_as_path))
    src_folder = list(filter(lambda x: x.endswith("src/main"), all_parents_as_str))[0]
    return Path(src_folder).joinpath("../../output/test").resolve()


def read_csv_as_dataframe(file_path, program_arguments):
    columns = ["vehicle_id", "carType", "travel_time", "distance", "free_flow_travel_time", "departure_time", "start_x",
               "start_y", "end_x", "end_y"]
    original_df = pd.read_csv(file_path, skiprows=1, names=columns)

    travel_distance_interval_in_meters = search_argument("--travelDistanceIntervalInMeters", program_arguments)
    if travel_distance_interval_in_meters is not None:
        travel_distance_values = re.findall("(\d+\.?\d*)", travel_distance_interval_in_meters)
        if len(travel_distance_values) != 2:
            raise Exception(
                f"travelDistanceIntervalInMeters({travel_distance_interval_in_meters}) does not contain a valid range")
        try:
            travel_distance_start = float(travel_distance_values[0])
            travel_distance_end = float(travel_distance_values[1])
            print(f"**** Filtering travelDistanceIntervalInMeters: {travel_distance_interval_in_meters}")
            original_df = original_df[(original_df['distance'] >= travel_distance_start) &
                                      (original_df['distance'] <= travel_distance_end)]
        except ValueError:
            raise Exception(
                f"travelDistanceIntervalInMeters({travel_distance_interval_in_meters}) does not contain a valid range")

    departure_time_range = search_argument("--departureTimeIntervalInSeconds", program_arguments)
    if departure_time_range is not None:
        departure_time_range = departure_time_range[1:-1]
        departure_time_start = float(departure_time_range.split(",")[0])
        departure_time_end = float(departure_time_range.split(",")[1])
        print(f"**** Filtering departureTimeIntervalInSeconds: [{departure_time_start},{departure_time_end}]")
        original_df = original_df[(original_df['departure_time'] >= departure_time_start) & (
                original_df['departure_time'] <= departure_time_end)]

    area_bound_box = search_argument("--areaBoundBox", program_arguments)
    if area_bound_box is not None:
        def filter_by_bound_box(bound_box):
            def internal_filter(row):
                if not len(row):
                    return None
                point1 = Point(row.start_x, row.start_y)
                point2 = Point(row.end_x, row.end_y)
                return bound_box.contains_point(point1) or bound_box.contains_point(point2)
            return internal_filter
        area_bound_box = BoundBox.from_str(area_bound_box)
        print(f"**** Filtering area BoundBox: {area_bound_box}.")
        series_filtered_by_bound_box = original_df.apply(filter_by_bound_box(area_bound_box), axis=1)
        original_df.index = series_filtered_by_bound_box.index

    sample_size = search_argument("--sampleSize", program_arguments)
    if sample_size is not None:
        sample_size = int(sample_size)
        sample_seed = int(search_argument("--sampleSeed", program_arguments))
        print(f"**** Sampling data. sampleSize: [{sample_size}]. sampleSeed: [{sample_seed}]")
        if len(original_df) >= sample_size:
            original_df = original_df.sample(n=sample_size, random_state=sample_seed)
        else:
            print(f"**** INFO: The sampleSize is bigger than current size({original_df.size}) of dataframe. SampleSize was ignored.")

    return original_df


class Point:
    def __init__(self, x, y):
        self.x = x
        self.y = y

    def __str__(self):
        return "(" + str(self.x) + "," + str(self.y) + ")"


class BoundBox:
    def __init__(self, topLeft, rightBottom):
        self.topLeft = topLeft
        self.rightBottom = rightBottom

    def from_str(value):
        values = re.findall("(-?\d+\.?\d*)", value)
        if len(values) != 4:
            msg = f"Invalid BoundBox input: [{value}]. It must have 4 float values"
            raise Exception(msg)
        a = Point(float(values[0]), float(values[1]))
        b = Point(float(values[2]), float(values[3]))
        return BoundBox(a, b)

    def contains_point(self, point):
        condition1 = self.topLeft.x <= point.x <= self.rightBottom.x
        condition2 = self.topLeft.y <= point.y <= self.rightBottom.y
        return condition1 and condition2

    def __str__(self):
        return f"[({self.topLeft.x},{self.topLeft.y})({self.rightBottom.x},{self.rightBottom.y})]"


if __name__ == "__main__":
    main(sys.argv)
