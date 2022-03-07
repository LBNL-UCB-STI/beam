import helper
import os

mnl_lines = ["mnlStatus,parkingZoneId,chargingPointType,parkingType,costInDollars\n"]
output_log_file = os.path.expanduser('~/Data/GEMINI/2022Mars-Calibration/beamLog.out.txt')
with open(output_log_file) as infile:
    for line in infile:
        if "SAMPLED: " in line:
            mnl_lines.append("sampled,"+line.split("SAMPLED: ")[1])
        elif "CHOSEN: " in line:
            mnl_lines.append("chosen,"+line.split("CHOSEN: ")[1])

mnl_output_log_file = os.path.expanduser('~/Data/GEMINI/2022Mars-Calibration/mnl-beamLog.csv')
with open(mnl_output_log_file, 'w') as f:
    f.writelines(mnl_lines)
print("END")
