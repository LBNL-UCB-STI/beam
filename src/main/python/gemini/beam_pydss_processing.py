import os

basefolder = ""
beamLog_out = "{}/beamLog.out".format(basefolder)
beamLog_out_csv = "{}/beamLog.csv".format(basefolder)
file1 = open(beamLog_out, 'r')
Lines = file1.readlines()
file2 = open(beamLog_out_csv, 'w')
# Strips the newline character
for line in Lines:
    if "CHOICE-SET" in line:
        file2.writelines(line)
        print(line)
file1.close()
file2.close()
print("END")