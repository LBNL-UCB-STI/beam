import os

basefolder1 = "/Users/haitamlaarabi/Documents/Workspace/beam/production-gemini-develop/output/sf-light/"
basefolder2 = basefolder1+"/sf-light-1k-xml__2021-04-25_21-39-08_rdq"
beamLog_out = "{}/beamLog.out".format(basefolder2)
beamLog_out_csv = "{}/beamLog.csv".format(basefolder2)
file1 = open(beamLog_out, 'r')
Lines = file1.readlines()
file2 = open(beamLog_out_csv, 'w')
# Strips the newline character
for line in Lines:
    if "estimatePowerDemandInKW," in line or "getPartialSkim," in line:
        file2.writelines(line)
        print(line)
file1.close()
file2.close()
print("END")