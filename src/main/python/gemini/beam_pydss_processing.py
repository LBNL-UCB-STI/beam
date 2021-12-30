import os

basefolder = "/Users/haitamlaarabi/Data/GEMINI/2021March22/370k-warmstart/output"
beamLog_out = "{}/beamLog.out".format(basefolder)
beamLog_out_csv = "{}/beamLog.csv".format(basefolder)
file1 = open(beamLog_out, 'r')
Lines = file1.readlines()
file2 = open(beamLog_out_csv, 'w')
# Strips the newline character
for line in Lines:
    if "DELETE-THIS-" in line:
        file2.writelines(line)
        print(line)
file1.close()
file2.close()
print("END")