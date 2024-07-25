import pandas as pd
import pyarrow as pa
import pyarrow.csv as pv
import pyarrow.compute as pc
import matplotlib.pyplot as plt
import numpy as np
import time
import os


def read_skims_emissions(skims_file):
    # Define the schema based on the provided dtypes
    schema = pa.schema([
        ('hour', pa.int64()),
        ('linkId', pa.int64()),
        ('tazId', pa.string()),
        ('vehicleTypeId', pa.string()),
        ('emissionsProcess', pa.string()),
        ('speedInMps', pa.float64()),
        ('energyInJoule', pa.float64()),
        ('observations', pa.int64()),
        ('iterations', pa.int64()),
        ('CH4', pa.float64()),
        ('CO', pa.float64()),
        ('CO2', pa.float64()),
        ('HC', pa.float64()),
        ('NH3', pa.float64()),
        ('NOx', pa.float64()),
        ('PM', pa.float64()),
        ('PM10', pa.float64()),
        ('PM2_5', pa.float64()),
        ('ROG', pa.float64()),
        ('SOx', pa.float64()),
        ('TOG', pa.float64())
    ])

    # Read options
    read_options = pv.ReadOptions(use_threads=True)
    parse_options = pv.ParseOptions(delimiter=',')
    convert_options = pv.ConvertOptions(column_types=schema)

    # Read the CSV file
    start_time = time.time()
    table = pv.read_csv(skims_file,
                        read_options=read_options,
                        parse_options=parse_options,
                        convert_options=convert_options)
    end_time = time.time()

    print(f"Time taken to read the file: {end_time - start_time:.2f} seconds")

    # Print the first row (equivalent to head(1))
    print("First row:")
    print(table.slice(0, 1).to_pandas())

    return table


# Function to create plot
def create_plot(grouped_data, pollutants, title):
    fig, ax = plt.subplots(figsize=(15, 10))
    bottom = np.zeros(len(grouped_data))
    for pollutant in pollutants:
        ax.bar(grouped_data.index, grouped_data[pollutant], bottom=bottom, label=pollutant)
        bottom += grouped_data[pollutant]
    ax.set_title(title, fontsize=16)
    ax.set_xlabel('Emissions Process', fontsize=12)
    ax.set_ylabel('Total Emissions (tons)', fontsize=12)
    plt.xticks(rotation=45, ha='right')
    ax.legend(title='Pollutants', bbox_to_anchor=(1.05, 1), loc='upper left')
    plt.tight_layout()
    return fig


area = "sfbay"
batch = "2024-01-23"
pollutants = ['CH4', 'CO', 'CO2', 'HC', 'NH3', 'NOx', 'PM', 'PM10', 'PM2_5', 'ROG', 'SOx', 'TOG']
input_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-runs/{batch}")


skims_2018_Baseline = read_skims_emissions(f"{input_dir}/2018_Baseline_TrAP/0.skimsEmissions.csv.gz")
# skims_2018_Baseline_noFT = read_skims_emissions(f"{input_dir}/2018_Baseline_TrAP_noFT/0.skimsEmissions.csv.gz")
# skims_2050_HOPhighp2 = read_skims_emissions(f"{input_dir}/2050_HOPhighp2_TrAP/0.skimsEmissions.csv.gz")

mask = pc.invert(pc.match_substring(skims_2018_Baseline['vehicleTypeId'], pattern="--TRUCK--"))
filtered_skims_2018_Baseline = skims_2018_Baseline.filter(mask)

df = filtered_skims_2018_Baseline.to_pandas()

# ## MAIN ##

# convert grams to tons
for pollutant in pollutants:
    df[pollutant] = df[pollutant] / 1e6

# Group by emissionsProcess and sum the pollutants
grouped = df.groupby('emissionsProcess')[pollutants].sum()

# Create the stacked bar plot
fig, ax = plt.subplots(figsize=(15, 10))
bottom = np.zeros(len(grouped))

for pollutant in pollutants:
    ax.bar(grouped.index, grouped[pollutant], bottom=bottom, label=pollutant)
    bottom += grouped[pollutant]

ax.set_title('Total Freight Emissions by Process and Pollutant', fontsize=16)
ax.set_xlabel('Emissions Process', fontsize=12)
ax.set_ylabel('Total Emissions (tons)', fontsize=12)

# Rotate x-axis labels for better readability
# plt.xticks(rotation=45, ha='right')
ax.legend(title='Pollutants', bbox_to_anchor=(1.05, 1), loc='upper left')
plt.tight_layout()
plt.show()


pollutants_without_co2 = [p for p in pollutants if p != 'CO2']
grouped_without_co2 = df.groupby('emissionsProcess')[pollutants_without_co2].sum()
fig2 = create_plot(grouped_without_co2, pollutants_without_co2, 'Total Emissions by Process and Pollutant (Excluding CO2)')
fig2.savefig(f"{input_dir}/2018_Baseline_TrAP/emissions_by_process_and_pollutant_without_co2.png", dpi=300, bbox_inches='tight')
plt.close('all')


df.to_csv(f"{input_dir}/2018_Baseline_TrAP/skims_temp.csv", index=False)
