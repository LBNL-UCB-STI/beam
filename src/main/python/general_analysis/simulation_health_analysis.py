import os
import sys
from glob import glob


detectors = {
                "deadLetter": lambda line: "DeadLetter" in line,
                "actor died" : lambda line: "terminated unexpectedly" in line,
                "warn": lambda line: " WARN " in line, 
                "error": lambda line: " ERROR " in line, 
                "stacktrace": lambda line: line.startswith("\tat ")   
            }
beam_home = sys.argv[1]
print(beam_home)
os.chdir(beam_home)
os.system("./gradlew :run -PappArgs=\"['--config', 'test/input/beamville/beam.conf']\"")

log_file_location = glob(beam_home+"/output/beamville/*/beamLog.out")

with open(log_file_location[0]) as file:
    file = file.readlines()

matric_log = {}
stacktrace_count = 0
for line in file:
    
    if detectors["stacktrace"](line):
        stacktrace_count = stacktrace_count + 1
        continue
    elif stacktrace_count > 0:
        matric = matric_log.get("stacktrace", [])
        matric.append(stacktrace_count)
        matric_log["stacktrace"] = matric
        stacktrace_count = 0
        continue

    for key,value in detectors.items():
        if value(line):
            matric = matric_log.get(key, [])
            matric.append(line)
            matric_log[key] = matric
            break

with open('RunHealthAnalysis.txt', 'w') as file:
    for detector in detectors:
        file.write(f"{detector},{len(matric_log.get(detector, []))}\n")

