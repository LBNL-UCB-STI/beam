import string


def chargingCapFromString(chargingCap: string) -> float:
    # determine the charging capability based on a string
    s1 = chargingCap.find("(")
    s2 = chargingCap.find("|")
    VehicleMaxPower = float(chargingCap[s1+1:s2])
    if VehicleMaxPower > 1000 or VehicleMaxPower< 10:
        print("Warning: There could be a vehicle max power reading mistake")
    return VehicleMaxPower