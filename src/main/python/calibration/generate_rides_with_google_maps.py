import pandas as pd
import getopt, sys
from pathlib import Path
import glob
import random
import numpy as np
from tabulate import tabulate


def main(args):
    program_arguments = parse_parameters(args)
    input_file_path = Path(search_argument("--inputFile", program_arguments))
    output_file = output_name(input_file_path)
    convert_file(input_file_path, output_file, program_arguments)


def search_argument(argument_name, arguments):
    the_tuple = filter(lambda x: x[0] == argument_name, arguments)
    result = next(the_tuple, None)
    if result is None:
        return None
    else:
        return result[1]


def parse_parameters(args):
    short_options = ""
    long_options = ["help", "inputFile=", "sampleSize=", "sampleSeed=", "maxDistance=", "departureTimeRange="]
    try:
        arguments, _ = getopt.getopt(args[1:], short_options, long_options)

        if search_argument("--inputFile", arguments) is None:
            arguments.insert(0, ("--inputFile", select_input(args[0])))

        if search_argument("--sampleSize", arguments) is not None \
                and search_argument("--sampleSeed", arguments) is None:
            seed = random.randint(0, 4294967296)
            print(f"Seed not specified. Using the number [{seed}] as seed.")
            arguments.append(("--sampleSeed", str(seed)))

        return arguments
    except getopt.error as err:
        print(str(err))
        sys.exit(2)


def output_name(input_file_path):
    input_name = input_file_path.name
    tmp_list = input_name.rsplit(".")[:-2]
    tmp_list.append("output")
    tmp_list.append("csv")
    result_file_name = ".".join(tmp_list)
    return input_file_path.parent.joinpath(result_file_name).resolve()


def convert_file(input_file_name, output_file_name, program_arguments):
    df = read_csv_as_dataframe(input_file_name, program_arguments)
    urls_list = generate_urls_as_list(
        df['start_x'].values.tolist(),
        df['start_y'].values.tolist(),
        df['end_x'].values.tolist(),
        df['end_y'].values.tolist()
    )
    df['google_link'] = pd.Series(data=urls_list, dtype='str', index=df.index)
    print(tabulate(df, headers='keys', tablefmt='psql'))

    df.to_csv(output_file_name, header=True, index=False)
    print(f"File [{output_file_name.absolute()}] created.")


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
        result = Path(found_files[selected_index])
        return result
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


def url_from_tuple(tuple_value):
    start_x = tuple_value[0]
    start_y = tuple_value[1]
    end_x = tuple_value[2]
    end_y = tuple_value[3]
    result = f"https://www.google.com/maps/dir/{start_y}%09{start_x}/{end_y}%09{end_x}"
    return str(result)


def generate_urls_as_list(start_x_list, start_y_list, end_x_list, end_y_list):
    tuples = list(zip(start_x_list, start_y_list, end_x_list, end_y_list))
    return np.array(list(map(url_from_tuple, tuples)))


def read_csv_as_dataframe(file_path, program_arguments):
    columns = ["vehicle_id", "carType", "travel_time", "distance", "free_flow_travel_time", "departure_time", "start_x",
               "start_y", "end_x", "end_y"]
    original_df = pd.read_csv(file_path, skiprows=1, names=columns)

    sample_size = search_argument("--sampleSize", program_arguments)
    if sample_size is not None:
        sample_size = int(sample_size)
        sample_seed = int(search_argument("--sampleSeed", program_arguments))
        print(f"**** Sampling data. sampleSize: [{max_distance}]. sampleSeed: [{sample_seed}]")
        original_df = original_df.sample(n=sample_size, random_state=sample_seed)

    max_distance = search_argument("--maxDistance", program_arguments)
    if max_distance is not None:
        max_distance = float(max_distance)
        print(f"**** Filtering maxDistance: [{max_distance}]")
        original_df = original_df[original_df['distance'] <= max_distance]

    departure_time_range = search_argument("--departureTimeRange", program_arguments)
    if departure_time_range is not None:
        departure_time_range = departure_time_range[1:-1]
        departure_time_start = float(departure_time_range.split(",")[0])
        departure_time_end = float(departure_time_range.split(",")[1])
        print(f"**** Filtering departureTimeRange: [{departure_time_start},{departure_time_end}]")
        original_df = original_df[(original_df['departure_time'] >= departure_time_start) & (original_df['departure_time'] <= departure_time_end)]

    return original_df


if __name__ == "__main__":
    main(sys.argv)
