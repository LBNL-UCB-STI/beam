import helper
import os

mnl_lines = ["mnlStatus,requestId,parkingZoneId,chargingPointType,parkingType,costInDollars\n"]
mnl_search_lines = ["parkingZoneId,geoId,parkingType,chargingPointType,pricingModel,reservedFor,stallsAvailable,"
                    "maxStalls,vehicleId,parkingDuration,activityType,valueOfTime,requestId,isEV,rideHailFastChargingOnly,"
                    "validChargingCapability,hasAvailability,validParkingType,isValidTime,isValidVehicleManager\n"]
mnl_param_lines = ["parkingZoneId,geoId,parkingType,chargingPointType,pricingModel,reservedFor,stallsAvailable,"
                   "maxStalls,vehicleId,parkingDuration,activityType,valueOfTime,requestId,costInDollars,RangeAnxietyCost,"
                   "WalkingEgressCost,ParkingTicketCost,HomeActivityPrefersResidentialParking\n"]
output_log_file = os.path.expanduser('~/Data/GEMINI/2022Mars-Calibration/beamLog.out.txt')
with open(output_log_file) as infile:
    for line in infile:
        if "SAMPLED: " in line:
            mnl_lines.append("sampled,"+line.split("SAMPLED: ")[1])
        elif "CHOSEN: " in line:
            mnl_lines.append("chosen,"+line.split("CHOSEN: ")[1])
        elif "SEARCH: " in line:
            mnl_search_lines.append(line.split("SEARCH: ")[1])
        elif "PARAM: " in line:
            mnl_param_lines.append(line.split("PARAM: ")[1])

with open(os.path.expanduser('~/Data/GEMINI/2022Mars-Calibration/mnl-beamLog.csv'), 'w') as f:
    f.writelines(mnl_lines)
with open(os.path.expanduser('~/Data/GEMINI/2022Mars-Calibration/mnl-search-beamLog.csv'), 'w') as f:
    f.writelines(mnl_search_lines)
with open(os.path.expanduser('~/Data/GEMINI/2022Mars-Calibration/mnl-param-beamLog.csv'), 'w') as f:
    f.writelines(mnl_param_lines)
print("END")
