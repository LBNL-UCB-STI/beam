import os
import requests
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
log_file_location = glob(beam_home + "/output/*/*/beamLog.out")
log_file_location.sort(key=lambda x: os.path.getmtime(x), reverse=True)
with open(log_file_location[0]) as file:
    file = file.readlines()

matric_log = {}
stacktrace_count = 0
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

with open('RunHealthAnalysis.txt', 'w') as file:
    for detector in detectors:
        file.write(detector+","+str(len(matric_log.get(detector, [])))+"\n")

beam_output_path = os.path.dirname(log_file_location[0])
copyfile('RunHealthAnalysis.txt', beam_output_path+"/runHealthAnalysis.txt")