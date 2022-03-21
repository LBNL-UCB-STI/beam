import os
from glob import glob
from shutil import copyfile



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
beam_home = os.getcwd()


def handleJavaErrorLine(line):
    tokens = line.split("\"")
    if len(tokens) > 0:
        return tokens[1].replace('%s', '')


def handleScalaToken(tokens):
    out_result = []
    if len(tokens) > 1:
        split_error_lines = tokens[1].strip().split('$')
        first_word = True
        for split_error_line in split_error_lines:
            words = split_error_line.strip().replace('\\n', '').split(" ")
            word_collector = words if first_word else words[1:]
            if first_word:
                first_word = False
            if len(word_collector) > len(out_result):
                out_result = word_collector
    return " ".join(out_result)


def handleScalaErrorLine(line):
    tokens = line.split("\"")
    if "(s\"" in line or "(\ns\"" in line or "(f\"" in line:
        return handleScalaToken(tokens)
    elif len(tokens) > 1:
        return tokens[1]


def detect_all_error_types():
    error_list = []
    for dir_path, sub_dir_path, source_files in os.walk(beam_home + '/src/main/'):
        for source_file in source_files:
            if source_file.endswith('.java'):
                source = open(dir_path + '/' + source_file, "r")
                for line in source:
                    if 'new RuntimeException' in line or 'new Exception' in line:
                        error_list.append(handleJavaErrorLine(line))
            if source_file.endswith('.scala'):
                source = open(dir_path + '/' + source_file, "r")
                for line in source:
                    if 'new RuntimeException' in line or 'new Exception' in line:
                        error_list.append(handleScalaErrorLine(line))
    return error_list


log_file_location = glob(beam_home + "/output/*/*/beamLog.out")
log_file_location.sort(key=lambda x: os.path.getmtime(x), reverse=True)
with open(log_file_location[0]) as file:
    file = file.readlines()

matric_log = {}
stacktrace_count = 0
error_types = detect_all_error_types()

for line in file:
    # treating stacktrace detector specially because 1 stacktrace consist multiple lines of stacktrace
    if detectors["stacktrace"](line):
        if stacktrace_count == 0:
            matric = matric_log.get("stacktrace", [])
            matric.append(stacktrace_count)
            matric_log["stacktrace"] = matric
        stacktrace_count = stacktrace_count + 1
        continue
    elif stacktrace_count > 0:
        stacktrace_count = 0
        continue

    # iterating each detector and evaluating if line consist that detector
    for key, value in detectors.items():
        if value(line):
            matric = matric_log.get(key, [])
            matric.append(line)
            matric_log[key] = matric
            break

    for error_type in error_types:
        if error_type is not None and error_type in line:
            matric = matric_log.get(error_type, [])
            matric.append(line)
            matric_log[error_type] = matric

with open('RunHealthAnalysis.txt', 'w') as file:
    for detector in detectors:
        file.write(detector + "," + str(len(matric_log.get(detector, []))) + "\n")

beam_output_path = os.path.dirname(log_file_location[0])
copyfile('RunHealthAnalysis.txt', beam_output_path + "/runHealthAnalysis.txt")


