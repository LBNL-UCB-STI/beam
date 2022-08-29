#ADD MANY 50DC 150DC 250DC 350DC
headerfile = "taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor"

with open('sfbay_taz_unlimited_parking_stalls.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Residential,Block,NoCharger,9999999,0,Any" + "\n")
        csv_writer.write(f"{x},Workplace,Block,NoCharger,9999999,0,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,NoCharger,9999999,0,Any" + "\n")


with open('sfbay_taz_unlimited_charging_point.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Residential,Block,HomeLevel1(1.8|AC),9999999,0.45,Any" + "\n")
        csv_writer.write(f"{x},Residential,Block,HomeLevel2(7.2|AC),9999999,1.8,Any" + "\n")

        csv_writer.write(f"{x},Workplace,Block,WorkLevel2(7.2|AC),9999999,14.4,Any" + "\n")

        csv_writer.write(f"{x},Public,Block,PublicLevel2(7.2|AC),9999999,19.3,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,PublicFC(150|DC),9999999,490.98,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,PublicFC(200|DC),9999999,654.64,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,PublicXFC(300|DC),9999999,981.96,Any" + "\n")
        csv_writer.write(f"{x},Public,Block,PublicXFC(400|DC),9999999,1309.28,Any" + "\n")


with open('sfbay_taz_unlimited_depots.csv', mode='w') as csv_writer:
    csv_writer.write(headerfile+"\n")

    for x in range(1, 1455):
        csv_writer.write(f"{x},Public,FlatFee,DepotFC(150.0|DC),9999999,490.98,ridehail(GlobalRHM)" + "\n")
        csv_writer.write(f"{x},Public,FlatFee,DepotXFC(200.0|DC),9999999,654.64,ridehail(GlobalRHM)" + "\n")
        csv_writer.write(f"{x},Public,FlatFee,DepotXFC(300.0|DC),9999999,981.96,ridehail(GlobalRHM)" + "\n")
        csv_writer.write(f"{x},Public,FlatFee,DepotXFC(400.0|DC),9999999,1309.28,ridehail(GlobalRHM)" + "\n")


