import csv
import glob
import os
from os import listdir

# this is a modified copy of https://github.com/LBNL-UCB-STI/beam/blob/develop/src/main/python/general_analysis/simulation_health_analysis.py
# it parses beamLog.out files from simulations/ folder and counts the amount of time each each error log is met
# then it produces the merged.csv file, that can help with comparing different runs against each other


# detectors is a dictionary that consist key as health metric and
# value as lambda function that can detect health metric from line.
# if we need to add more detector then we have to add that detector in this dictionary
detectors = {
    "deadLetter": lambda line: "DeadLetter" in line,
    "actorDied": lambda line: "terminated unexpectedly" in line,
    "warn": lambda line: " WARN " in line,
    "error": lambda line: " ERROR " in line,
    "stacktrace": lambda line: line.startswith("\tat ")
}

complicated_error_patterns = {
    " State:": 'State:FinishingModeChoice PersonAgent:X  Current tour vehicle is the same as the one being removed: X - X]',
    " Message ": 'Message [beam.agentsim.agents.vehicles.ReservationRequest] from Actor',
    " [X] Vehicle does not have sufficient fuel to make trip": 'Vehicle does not have sufficient fuel to make trip, allowing trip to happen and setting fuel level negative: vehicle body',
    " Person Actor[akka": 'Person Actor[akka://ClusterSystem/user/BeamMobsim.iteration/population'
}


def replace_all_digits(log):
    digit_has_been_met = False
    result = ""
    for x in log:
        if x.isdigit():
            if not digit_has_been_met:
                digit_has_been_met = True
                result += 'X'
            else:
                continue
        else:
            if digit_has_been_met:
                digit_has_been_met = False
            result += x
    return result


beam_home = os.getcwd()
file_names = listdir(beam_home + "/simulations/")
output_dir = beam_home + "/output/"
if not os.path.exists(output_dir):
    os.makedirs(output_dir)

list_matrices = {}
result_merged_files = {}

for file_name in file_names:
    log_file_location = glob.glob(beam_home + "/simulations/" + file_name)
    log_file_location.sort(key=lambda x: os.path.getmtime(x), reverse=True)
    with open(log_file_location[0]) as file:
        file = file.readlines()

    matrix_log = {}
    stacktrace_count = 0
    for line in file:
        # treating stacktrace detector specially because 1 stacktrace consist multiple lines of stacktrace
        if detectors["stacktrace"](line):
            if stacktrace_count == 0:
                matric_dict = matrix_log.get("stacktrace", [])
                matric_dict.append(stacktrace_count)
                matrix_log["stacktrace"] = matric_dict
            stacktrace_count = stacktrace_count + 1
            continue
        elif stacktrace_count > 0:
            stacktrace_count = 0
            continue

        # iterating each detector and evaluating if line consist that detector
        for log_level, value in detectors.items():
            if value(line):
                matric = matrix_log.get(log_level, [])
                matric.append(line)
                matrix_log[log_level] = matric
                break

    matrix_log_with_count = {}
    for key in matrix_log:
        errors = matrix_log[key]
        errors_map = {}
        for error in errors:
            log_level = key.upper() if key != 'actorDied' else "INFO"
            split_by_log_level = error.split(log_level)
            if len(split_by_log_level) > 1:
                split_by_hyphen = split_by_log_level[1].split("-", 1)
                log_message = split_by_hyphen[1]

            modified_error = replace_all_digits(log_message)
            for pattern, text in complicated_error_patterns.items():
                if modified_error.startswith(pattern):
                    modified_error = text

            modified_error = modified_error.strip()
            if modified_error == "":
                continue
            if modified_error in errors_map:
                errors_map[modified_error] += 1
            else:
                errors_map[modified_error] = 1
        matrix_log_with_count[key] = errors_map

    file_name_split = file_name.split("-beamLog")[0]

    # uncomment if you need a separate file for each beamLog.out
    # with open(output_dir + file_name_split + ".csv", 'w') as outf:
    #     writer = csv.writer(outf)
    #     writer.writerow(["LOG_LEVEL", "MESSAGE", "COUNT"])
    #     for key, out_value in matric_log_with_count.items():
    #         for item in out_value.items():
    #             writer.writerow([key, item[0], item[1]])
    list_matrices[file_name_split] = matrix_log_with_count


list_files_ordered = sorted(list_matrices.keys())
for index, file_name in enumerate(list_files_ordered):
    matrix = list_matrices.get(file_name)
    # key = error/warn, value = dict of errors
    for key, value in matrix.items():
        if key not in result_merged_files:
            if index != 0:
                new_dict = {}
                for k, v in matrix[key].items():
                    new_dict[k] = [0] * len(list_files_ordered)
                    new_dict[k][index] = v
                result_merged_files[key] = new_dict
                continue

            dict = {}
            # k = error text, v = count of errors for this file
            for k, v in value.items():
                dict[k] = [0] * len(list_files_ordered)
                dict[k][index] = v
            result_merged_files[key] = dict
        else:
            if key in result_merged_files:
                # k = error text, v = count of errors for this file
                for k, v in value.items():
                    if k in result_merged_files[key]:
                        result_merged_files[key][k][index] = v
                    else:
                        result_merged_files[key][k] = [0] * len(list_files_ordered)
                        result_merged_files[key][k][index] = v


with open(output_dir + "merged.csv", 'w') as outf:
    writer = csv.writer(outf)
    writer.writerow(["LOG_LEVEL", "MESSAGE", *list_files_ordered])
    for key, out_value in result_merged_files.items():
        for item in out_value.items():
            writer.writerow([key, item[0], *item[1]])
