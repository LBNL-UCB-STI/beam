import pandas as pd
import sys
from pathlib import Path
import glob


def main(args):
    if len(args) > 1:
        input_file_path = Path(args[1])
        if not Path.is_file(input_file_path):
            print(f"The input file [{input_file_path}] does not exist")
            sys.exit(1)
    else:
        print("Input file was not provided")
        input_file_path = select_input(args[0])
        if input_file_path is None:
            print("No possible file was found.")
            sys.exit(0)

    output_file = output_name(input_file_path)
    convert_file(input_file_path, output_file)
    print(f"The file [{output_file}] was created.")


def output_name(input_file_path):
    input_name = input_file_path.name
    tmp_list = input_name.rsplit(".")[:-2]
    tmp_list.append("output")
    tmp_list.append("csv")
    result_file_name = ".".join(tmp_list)
    return input_file_path.parent.joinpath(result_file_name).resolve()


def convert_file(input_file_name, output_file_name):
    df = read_csv_as_dataframe(input_file_name)
    urls_list = generate_urls_as_list(
        df['start_x'].values.tolist(),
        df['start_y'].values.tolist(),
        df['end_x'].values.tolist(),
        df['end_y'].values.tolist()
    )
    df['google_link'] = pd.Series(urls_list)
    df.to_csv(output_file_name, header=True, index=False)


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
    return f"https://www.google.com/maps/dir/{start_y}%09{start_x}/{end_y}%09{end_x}"


def generate_urls_as_list(start_x_list, start_y_list, end_x_list, end_y_list):
    tuples = list(zip(start_x_list, start_y_list, end_x_list, end_y_list))
    return list(map(url_from_tuple, tuples))


def read_csv_as_dataframe(file_path):
    columns = ["vehicle_id", "carType", "travel_time", "distance", "free_flow_travel_time", "departure_time", "start_x",
               "start_y", "end_x", "end_y"]
    original_df = pd.read_csv(file_path, skiprows=1, names=columns)
    return original_df.copy()


if __name__ == "__main__":
    main(sys.argv)
