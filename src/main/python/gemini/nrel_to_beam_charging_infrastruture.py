import pandas as pd
import os
import random
from tqdm import tqdm

nrel_file_input = os.path.expanduser('~/Data/GEMINI/2022Feb/siting/init1-7_2022_Feb_03_wgs84.csv')
smart_file_input = os.path.expanduser("~/Data/GEMINI/stations/taz-parking-sparse-fast-limited-l2-150-lowtech-b.csv")
nrel_file_converted_input = os.path.expanduser(nrel_file_input.split(".")[0] + "_converted.csv")
smart_file_updated_input = os.path.expanduser(smart_file_input.split(".")[0] + "_updated.csv")
smart_file_with_fees_input = os.path.expanduser(nrel_file_input.split(".")[0] + "_withFees.csv.gz")


def read_file(filename):
    compression = None
    if filename.endswith(".gz"):
        compression = 'gzip'
    return pd.read_csv(filename, sep=",", index_col=None, header=0, compression=compression)


def convert_nrel_data(nrel_file, nrel_file_converted):
    if not os.path.exists(nrel_file_converted):
        data = read_file(nrel_file)
        data2 = data[["subSpace", "pType", "chrgType", "field_1", "household_id", "X", "Y", "housingTypes", "propertytype", "propertysubtype", "county"]]
        data2 = data2.rename(columns={
            "chrgType": "chargingPointType",
            "pType": "parkingType",
            "subSpace": "taz",
            "X": "locationX",
            "Y": "locationY",
            "housingTypes": "housingType",
            "propertytype": "propertyType",
            "propertysubtype": "propertySubType",
            "county": "county"
        })
        data2["parkingZoneId"] = ""
        data2["reservedFor"] = "Any"
        data2["pricingModel"] = "Block"
        data2["feeInCents"] = 0
        data2["numStalls"] = 1
        data2.loc[data2["household_id"].notna(), ['reservedFor']] = data2.loc[data2["household_id"].notna()].apply(
            lambda row1: "household(" + str(int(row1["household_id"])) + ")", axis=1)
        data2.loc[data2["field_1"].notna(), ['parkingZoneId']] = data2.loc[data2["field_1"].notna()].apply(
            lambda row2: "PEV-" + str(int(row2["taz"])) + "-" + str(int(row2["field_1"])), axis=1)
        nrel_data = data2.drop(columns=['household_id', 'field_1'])
        nrel_data.to_csv(nrel_file_converted, index=False)
        print("Reading nrel infrastructure done!")
        return nrel_data
    else:
        return read_file(nrel_file_converted)


# Reading fees
def reading_sf_bay_fees(smart_file, smart_file_updated):
    if not os.path.exists(smart_file_updated):
        smart_data = read_file(smart_file)
        smart_data["chargingPointType"] = "NoCharger"
        smart_data.loc[(smart_data["chargingType"] == "WorkLevel2(7.2|AC)") & (smart_data["parkingType"] == "Public"), ['chargingPointType']] = "publiclevel2(7.2|AC)"
        smart_data.loc[(smart_data["chargingType"] == "WorkLevel2(7.2|AC)") & (smart_data["parkingType"] == "Workplace"), ['chargingPointType']] = "worklevel2(7.2|AC)"
        smart_data.loc[smart_data["chargingType"] == "Custom(150.0|DC)", ['chargingPointType']] = "publicfc(150.0|DC)"
        smart_data.loc[smart_data["chargingType"] == "HomeLevel2(7.2|AC)", ['chargingPointType']] = "homelevel2(7.2|AC)"
        smart_data.loc[smart_data["chargingType"] == "HomeLevel1(1.8|AC)", ['chargingPointType']] = "homelevel1(1.8|AC)"
        smart_data = smart_data.drop(columns=["chargingType"])
        smart_data = smart_data.rename(columns={"ReservedFor": "reservedFor"})
        smart_data.to_csv(smart_file_updated, index=False)
        print("Reading Fees done!")
        return smart_data
    else:
        return read_file(smart_file_updated)


def assign_fees_to_infrastructure(nrel_data, fees_data, smart_file_with_fees):
    df_dict = nrel_data.to_dict('records')
    for row in tqdm(df_dict):
        charging_type_arg = row["chargingPointType"]
        if "fc" in charging_type_arg:
            charging_type_arg = "publicfc(150.0|DC)"
        filtered = fees_data.loc[(fees_data["taz"] == row["taz"]) &
                                 (fees_data["parkingType"] == row["parkingType"]) &
                                 (fees_data["chargingPointType"] == charging_type_arg)]
        if len(filtered.index) == 0:
            filtered = fees_data.loc[(fees_data["parkingType"] == row["parkingType"]) &
                                     (fees_data["chargingPointType"] == charging_type_arg)]
        pd.options.mode.chained_assignment = None
        filtered.loc[:, "numStalls"] = filtered.loc[:, "numStalls"].astype('int')
        tot_stalls = filtered["numStalls"].sum()
        cumulated = 0.0
        memorized_fee = 0.0
        rd_prob = random.uniform(0, 1)
        for row2 in filtered.itertuples():
            memorized_fee = row2.feeInCents
            cumulated = cumulated + float(row2.numStalls) / float(tot_stalls)
            if cumulated >= rd_prob:
                break
        if "(150.0|DC)" in row["chargingPointType"]:
            memorized_fee = memorized_fee * 1.6
        elif "(250.0|DC)" in row["chargingPointType"]:
            memorized_fee = memorized_fee * 2.2
        elif "(400.0|DC)" in row["chargingPointType"]:
            memorized_fee = memorized_fee * 3.1
        row["feeInCents"] = memorized_fee
    output = pd.DataFrame.from_dict(df_dict)
    output.reset_index(drop=True, inplace=True)
    output.to_csv(smart_file_with_fees, index=False)


nrel_data_output = convert_nrel_data(nrel_file_input, nrel_file_converted_input)
print("convert_nrel_data done!")
fees_data_output = reading_sf_bay_fees(smart_file_input, smart_file_updated_input)
print("reading_sf_bay_fees done!")
assign_fees_to_infrastructure(nrel_data_output, fees_data_output, smart_file_with_fees_input)
print("END")
