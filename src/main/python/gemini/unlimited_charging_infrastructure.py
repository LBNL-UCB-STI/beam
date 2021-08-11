

headerfile = "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,parkingZoneName,landCostInUSDPerSqft,reservedFor"

with open('gemini_taz_unlimited_parking_plugs_power_150kw.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Residential,Block,NoCharger,9999999,0,,," + "\n")
        csv_writer.write(f"{x},Residential,Block,HomeLevel1(1.8|AC),9999999,50,,," + "\n")
        csv_writer.write(f"{x},Residential,Block,HomeLevel2(7.2|AC),9999999,200,,," + "\n")

        csv_writer.write(f"{x},Workplace,Block,NoCharger,9999999,0,,," + "\n")
        csv_writer.write(f"{x},Workplace,Block,EVIWorkLevel2(7.2|AC),9999999,200,,," + "\n")

        csv_writer.write(f"{x},Public,Block,NoCharger,9999999,0,,," + "\n")
        csv_writer.write(f"{x},Public,Block,EVIPublicLevel2(7.2|AC)(7.2|AC),9999999,200,,," + "\n")
        csv_writer.write(f"{x},Public,Block,EVIPublicDCFast(150|DC),9999999,7500,,," + "\n")


with open('gemini_depot_unlimited_parking_power_150kw.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Public,FlatFee,FCSFast(150.0|DC),9999999,0,,,RideHail" + "\n")


