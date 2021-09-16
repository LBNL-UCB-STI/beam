

headerfile = "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor"

with open('gemini_taz_unlimited_parking_plugs_power.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Residential,Block,NoCharger,9999999,0,Any" + "\n")
        csv_writer.write(f"{x},Residential,Block,HomeLevel1(1.8|AC),9999999,50,Any" + "\n")
        csv_writer.write(f"{x},Residential,Block,HomeLevel2(7.2|AC),9999999,200,Any" + "\n")

        csv_writer.write(f"{x},Workplace,Block,NoCharger,9999999,0,Any" + "\n")
        csv_writer.write(f"{x},Workplace,Block,WorkLevel2(7.2|AC),9999999,200,Any" + "\n")

        csv_writer.write(f"{x},Public,Block,NoCharger,9999999,0,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,Custom(7.2|AC),9999999,200,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,Custom(150|DC),9999999,7500,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,Custom(250|DC),9999999,15000,Any" + "\n")


with open('gemini_depot_unlimited_parking_power.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Public,FlatFee,Custom(150.0|DC),9999999,0,ridehail(GlobalRHM)" + "\n")
        csv_writer.write(f"{x},Public,FlatFee,Custom(250.0|DC),9999999,0,ridehail(GlobalRHM)" + "\n")


