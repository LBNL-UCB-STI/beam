import os

beamLog_out = "/Users/haitamlaarabi/Desktop/beamLog.out.txt.csv"
beamLog_out_csv = "/Users/haitamlaarabi/Desktop/beamLog.csv"

file1 = open(beamLog_out, 'r')
Lines = file1.readlines()


file2 = open(beamLog_out_csv, 'w')



# Strips the newline character
for line in Lines:
    if ",msgToPublish," in line:
        file2.writelines(line)
        print(line)
file1.close()
file2.close()
print("END")