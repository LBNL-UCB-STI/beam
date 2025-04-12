import geopandas as gpd
import pandas as pd
import matplotlib.pyplot as plt
from shapely.geometry import Polygon
import time
import numpy as np
import zarr
import s3fs
import contextily as ctx
from matplotlib_scalebar.scalebar import ScaleBar
from matplotlib.patches import FancyArrowPatch
import geopandas as gpd
import matplotlib.pyplot as plt
import contextily as ctx
from matplotlib.patches import FancyArrowPatch
from mpl_toolkits.axes_grid1.anchored_artists import AnchoredSizeBar
from PIL import Image
import io
import numpy as np
from contextily.tile import _fetch_tile
from matplotlib.colors import LinearSegmentedColormap
import pyreadr

proj_string = "+proj=lcc +lat_0=40 +lon_0=-97 +lat_1=33 +lat_2=45 +x_0=0 +y_0=0 +ellps=sphere +units=m +no_defs +type=crs"

emis_shapefile_filepath = '../BEAM_to_EMFACT/isrm_polygon/isrm_polygon.shp'

emissionType = 'All'  # ['onNetwork','offNetwork','All']

scenarios = [

    #              'sfbay-baseline3_20240728',
    'sfbay-baseline3_20240728',

    'sfbay-tr_capacity_1_5-20230608',
    'sfbay-tr_capacity_1_5-20230608',
    'sfbay-tr_capacity_1_5-20230608',

    'sfbay-telecommuting-baseline-20230616',

]
scenarios2 = [
    #         'sfbay-cordon_flatrate_20241023',
    'sfbay-cordon_income_20241023',

    'sfbay-tr_capacity_1_5-20230608',
    'sfbay-wb-incentives-200-20230630',
    'sfbay-tr-discount-100-20230703',

    'sfbay-telecommuting-8p60-20230620',
]

inexus_filepaths = [

    #     'sfbay-baseline3_20240728/inexus/sfbay_baseline_default-1.0_2020__20240728.csv.gz',
    'sfbay-baseline3_20240728/inexus/sfbay_baseline_default-1.0_2020__20240728.csv.gz',

    'sfbay-tr_capacity_1_5-20230608/inexus/sfbay_repo_transitCapacity-1.5_2020__20230608.csv.gz',
    'sfbay-tr_capacity_1_5-20230608/inexus/sfbay_repo_transitCapacity-1.5_2020__20230608.csv.gz',
    'sfbay-tr_capacity_1_5-20230608/inexus/sfbay_repo_transitCapacity-1.5_2020__20230608.csv.gz',

    'sfbay-telecommuting-baseline-20230616/inexus/sfbay_baseline_default-1.0_2020__20230616.csv.gz',

]

inexus_filepaths2 = [
    #         'sfbay-cordon_flatrate_20241023/inexus/output/sfbay_baseline_default-1.0_2020__20241024.csv.gz',
    'sfbay-cordon_income_20241023/inexus/output/sfbay_baseline_default-1.0_2020__20241024.csv.gz',

    'sfbay-tr_capacity_1_5-20230608/inexus/sfbay_repo_transitCapacity-1.5_2020__20230608.csv.gz',
    'sfbay-wb-incentives-200-20230630/inexus/sfbay_incentives_walk and bike-1000_2020__20230630.csv.gz',
    'sfbay-tr-discount-100-20230703/inexus/sfbay_price_transit_price-0_2020__20230702.csv.gz',

    'sfbay-telecommuting-8p60-20230620/inexus/sfbay_baseline_default-1.0_2020__20230620.csv.gz',

]

scenario_labels = [
    #     'SFMTA Cordon Policy Flat Rate',
    'SFMTA Cordon Policy Income-Based',

    'Baseline',
    'Active Modes Incentives',
    'Transit Incentives',

    'Telecommuting']

bucket = 'beam-core-outputs'

# Primary PM2.5 to BC1 and BC3
is_BC = False
# Nox to NO2
is_NO2 = True


# Custom colormap with white at the center
def custom_colormap():
    colors = ["#0000ff", "#ffffff", "#ff0000"]
    n_bins = 100  # Discretizes the interpolation into bins
    cmap_name = 'custom_cmap'
    return LinearSegmentedColormap.from_list(cmap_name, colors, N=n_bins)


def custom_colormap2():
    colors = ["#ff0000", "#ffffff", "#0000ff"]
    n_bins = 100  # Discretizes the interpolation into bins
    cmap_name = 'custom_cmap'
    return LinearSegmentedColormap.from_list(cmap_name, colors, N=n_bins)


def load_emis_data(emis_filepath, emis_filepath2=None):
    print('load_emis_data ...')
    emis = pd.read_csv(emis_filepath, nrows=None)

    if emissionType == 'onNetwork':

        emis['tons_per_year_ROG'] = emis['tons_per_year_RUNEX_ROG'] + emis['tons_per_year_RUNLOSS_ROG']
        emis['tons_per_year_NOx'] = emis['tons_per_year_RUNEX_NOx']
        emis['tons_per_year_NH3'] = emis['tons_per_year_RUNEX_NH3']
        emis['tons_per_year_SOx'] = emis['tons_per_year_RUNEX_SOx']
        emis['tons_per_year_PM2_5'] = emis['tons_per_year_RUNEX_PM2_5'] + emis['tons_per_year_PMBW_PM2_5'] + emis[
            'tons_per_year_PMTW_PM2_5']
        emis['tons_per_year_CO2'] = emis['tons_per_year_RUNEX_CO2']

    elif emissionType == 'offNetwork':

        emis['tons_per_year_ROG'] = emis['tons_per_year_DIURN_ROG'] + emis['tons_per_year_HOTSOAK_ROG'] + emis[
            'tons_per_year_STREX_ROG']
        emis['tons_per_year_NOx'] = emis['tons_per_year_STREX_NOx']
        emis['tons_per_year_NH3'] = 0.0
        emis['tons_per_year_SOx'] = emis['tons_per_year_STREX_SOx']
        emis['tons_per_year_PM2_5'] = emis['tons_per_year_STREX_PM2_5']
        emis['tons_per_year_CO2'] = emis['tons_per_year_STREX_CO2']

    emis = emis[['ISRM', 'tons_per_year_ROG', 'tons_per_year_NOx', 'tons_per_year_NH3', 'tons_per_year_SOx',
                 'tons_per_year_PM2_5', 'tons_per_year_CO2']]
    emis = emis.groupby('ISRM').sum().reset_index()

    if emis_filepath2 != emis_filepath:

        emis2 = pd.read_csv(emis_filepath2, nrows=None)

        if emissionType == 'onNetwork':

            emis2['tons_per_year_ROG'] = emis2['tons_per_year_RUNEX_ROG'] + emis2['tons_per_year_RUNLOSS_ROG']
            emis2['tons_per_year_NOx'] = emis2['tons_per_year_RUNEX_NOx']
            emis2['tons_per_year_NH3'] = emis2['tons_per_year_RUNEX_NH3']
            emis2['tons_per_year_SOx'] = emis2['tons_per_year_RUNEX_SOx']
            emis2['tons_per_year_PM2_5'] = emis2['tons_per_year_RUNEX_PM2_5'] + emis2['tons_per_year_PMBW_PM2_5'] + \
                                           emis2['tons_per_year_PMTW_PM2_5']
            emis2['tons_per_year_CO2'] = emis2['tons_per_year_RUNEX_CO2']

        elif emissionType == 'offNetwork':

            emis2['tons_per_year_ROG'] = emis2['tons_per_year_DIURN_ROG'] + emis2['tons_per_year_HOTSOAK_ROG'] + emis2[
                'tons_per_year_STREX_ROG']
            emis2['tons_per_year_NOx'] = emis2['tons_per_year_STREX_NOx']
            emis2['tons_per_year_NH3'] = 0.0
            emis2['tons_per_year_SOx'] = emis2['tons_per_year_STREX_SOx']
            emis2['tons_per_year_PM2_5'] = emis2['tons_per_year_STREX_PM2_5']
            emis2['tons_per_year_CO2'] = emis2['tons_per_year_STREX_CO2']

        emis2 = emis2[['ISRM', 'tons_per_year_ROG', 'tons_per_year_NOx', 'tons_per_year_NH3', 'tons_per_year_SOx',
                       'tons_per_year_PM2_5', 'tons_per_year_CO2']]
        emis2 = emis2.groupby('ISRM').sum().reset_index()
        merged_emis = pd.merge(emis, emis2, on='ISRM', how='outer', suffixes=('_1', '_2')).fillna(0)
        merged_emis['tons_per_year_ROG'] = (merged_emis['tons_per_year_ROG_2'] - merged_emis['tons_per_year_ROG_1'])
        merged_emis['tons_per_year_NOx'] = (merged_emis['tons_per_year_NOx_2'] - merged_emis['tons_per_year_NOx_1'])
        merged_emis['tons_per_year_NH3'] = (merged_emis['tons_per_year_NH3_2'] - merged_emis['tons_per_year_NH3_1'])
        merged_emis['tons_per_year_SOx'] = (merged_emis['tons_per_year_SOx_2'] - merged_emis['tons_per_year_SOx_1'])
        merged_emis['tons_per_year_PM2_5'] = (
                    merged_emis['tons_per_year_PM2_5_2'] - merged_emis['tons_per_year_PM2_5_1'])
        merged_emis['tons_per_year_CO2'] = (merged_emis['tons_per_year_CO2_2'] - merged_emis['tons_per_year_CO2_1'])

        calculate_emissions_differences(emis, emis2)

        return merged_emis

    else:

        return emis


def load_shape_data(emis_shapefile_filepath):
    print('load_shape_data ...')
    return gpd.read_file(emis_shapefile_filepath)


def calculate_emissions_differences(emis, emis2):
    print('calculate_emissions_differences ...')

    def calculate_change(old, new):
        return (new / old - 1) * 100

    # Define ISRM cordon zone and SF ranges
    cordon_ranges = [(1346, 1350), (1378, 1382), (1393, 1397), (1402, 1402), (1412, 1415)]
    SF_ranges = [(983, 986), (988, 991), (1002, 1005), (1039, 1048),
                 (1064, 1073), (1084, 1093), (1129, 1138), (1176, 1185),
                 (1221, 1230), (1253, 1264), (1291, 1302), (1340, 1345),
                 (1351, 1351), (1372, 1377), (1383, 1383), (1388, 1392),
                 (1407, 1411), (1416, 1416), (1053, 1053), (1113, 1113),
                 (1193, 1193)]

    # Function to check if ISRM is in the cordon zone or SF
    def is_in_cordon(isrm):
        return any(start <= isrm <= end for start, end in cordon_ranges)

    def is_in_SF(isrm):
        return any(start <= isrm <= end for start, end in SF_ranges)

    # Filter emis and emis2 for cordon zone, SF, and the rest
    emis_cordon = emis[emis['ISRM'].apply(is_in_cordon)]
    emis2_cordon = emis2[emis2['ISRM'].apply(is_in_cordon)]

    emis_SF = emis[emis['ISRM'].apply(is_in_SF)]
    emis2_SF = emis2[emis2['ISRM'].apply(is_in_SF)]

    emis_rest = emis[~emis['ISRM'].apply(lambda x: is_in_cordon(x) or is_in_SF(x))]
    emis2_rest = emis2[~emis2['ISRM'].apply(lambda x: is_in_cordon(x) or is_in_SF(x))]

    # Calculate sum of emissions for cordon, SF, and rest
    def calculate_totals(emis):
        return {
            'ROG': emis['tons_per_year_ROG'].sum(),
            'NOx': emis['tons_per_year_NOx'].sum(),
            'NH3': emis['tons_per_year_NH3'].sum(),
            'SOx': emis['tons_per_year_SOx'].sum(),
            'PM2.5': emis['tons_per_year_PM2_5'].sum(),
            'CO2': emis['tons_per_year_CO2'].sum(),
        }

    totals_cordon = calculate_totals(emis_cordon)
    totals_cordon2 = calculate_totals(emis2_cordon)

    totals_SF = calculate_totals(emis_SF)
    totals_SF2 = calculate_totals(emis2_SF)

    totals_rest = calculate_totals(emis_rest)
    totals_rest2 = calculate_totals(emis2_rest)

    # Calculate percentage changes
    def calculate_percentage_changes(totals1, totals2):
        return {key: calculate_change(totals1[key], totals2[key]) for key in totals1}

    delta_cordon = calculate_percentage_changes(totals_cordon, totals_cordon2)
    delta_SF = calculate_percentage_changes(totals_SF, totals_SF2)
    delta_rest = calculate_percentage_changes(totals_rest, totals_rest2)

    # Print the results
    print("\n--- Total Emissions ---")
    print("Cordon Zone:")
    for pollutant, value in totals_cordon.items():
        print(f"  {pollutant}: {value:.2f} tons/year")

    print("San Francisco (SF):")
    for pollutant, value in totals_SF.items():
        print(f"  {pollutant}: {value:.2f} tons/year")

    print("Rest of the Area:")
    for pollutant, value in totals_rest.items():
        print(f"  {pollutant}: {value:.2f} tons/year")

    print("\n--- Percentage Change in Emissions ---")
    print("Cordon Zone:")
    for pollutant, value in delta_cordon.items():
        print(f"  {pollutant}: {value:.2f}% change")

    print("San Francisco (SF):")
    for pollutant, value in delta_SF.items():
        print(f"  {pollutant}: {value:.2f}% change")

    print("Rest of the Area:")
    for pollutant, value in delta_rest.items():
        print(f"  {pollutant}: {value:.2f}% change")


def merge_emis_with_shape(merged_emis, gdf):
    print('merge_emis_with_shape ...')
    merged_emis['ISRM'] = merged_emis['ISRM'].astype(str).str.upper()
    gdf['isrm'] = gdf['isrm'].astype(str).str.upper()
    merged_emis = merged_emis.merge(gdf[['isrm', 'geometry']], left_on='ISRM', right_on='isrm')
    emis = gpd.GeoDataFrame(merged_emis, geometry='geometry')
    emis['ISRM'] = emis['ISRM'].astype(int)
    emis['area'] = emis.geometry.area
    return emis[['ISRM', 'tons_per_year_ROG', 'tons_per_year_NOx', 'tons_per_year_NH3', 'tons_per_year_SOx',
                 'tons_per_year_PM2_5', 'tons_per_year_CO2', 'geometry']]


def rect(i, w, s, e, n):
    x = [w[i], e[i], e[i], w[i], w[i]]
    y = [s[i], s[i], n[i], n[i], s[i]]
    return x, y


def poly(sr):
    ret = []
    w = sr["W"][:]
    s = sr["S"][:]
    e = sr["E"][:]
    n = sr["N"][:]
    for i in range(52411):
        x, y = rect(i, w, s, e, n)
        ret.append(Polygon([[x[0], y[0]], [x[1], y[1]], [x[2], y[2]], [x[3], y[3]], [x[4], y[4]]]))
    return ret


def load_inmap_data(url):
    print('load_inmap_data ...')
    fs = s3fs.S3FileSystem(anon=True, client_kwargs=dict(region_name='us-east-2'))
    sr = zarr.open(s3fs.S3Map(url, s3=fs, check=False), mode="r")
    return sr, poly(sr)


def process_emission_data(emis, sr, p):
    print('process_emission_data ...')
    TotalPop = sr['TotalPop'][0:52411]
    MortalityRate = sr['MortalityRate'][0:52411]
    df = pd.DataFrame({'Location': range(52411)})
    df['Location'] = df['Location'].astype(int)
    emis['ISRM'] = emis['ISRM'].astype(int)
    join_right_df = df.merge(emis, left_on='Location', right_on='ISRM', how='right')
    index = join_right_df.Location.tolist()
    ppl = np.unique(join_right_df.Location.tolist())
    num = range(0, len(ppl))
    dictionary = dict(zip(ppl, num))
    print(list(sr.keys()))
    if is_NO2:
        result = pyreadr.read_r('NOx_to_NO2_ISRM.RData')
        NO2_df = result['res.dat']

        # Convert the index and columns to integers
        NO2_df.index = NO2_df.index.astype(int)
        NO2_df.columns = NO2_df.columns.astype(int)

        # Define the full domain of indices for columns (0 to 55410)
        full_index = list(range(52411))

        # Reindex the DataFrame to cover the full domain, filling missing entries with 0
        NO2_full = NO2_df.reindex(index=full_index, columns=full_index, fill_value=0.0)

        # Subset the rows to only those in ppl (which should have length 1781)
        NO2_subset = NO2_full.loc[ppl, :]

        # Convert to a NumPy array and add a time dimension so that shape is (1, len(ppl), len(full_index))
        NO2 = NO2_subset.values
        NO2 = NO2[np.newaxis, :, :]
        print('NO2', NO2)
        print('NO2 shape', NO2.shape)
        print('NO2 data is allocated. Shape:', NO2.shape)
    SOA = sr['SOA'].get_orthogonal_selection(([0], ppl, slice(None)))
    print("SOA data is allocated.")
    print('SOA', SOA)
    print('SOA shape', SOA.shape)
    print(len(SOA))
    pNO3 = sr['pNO3'].get_orthogonal_selection(([0], ppl, slice(None)))
    print("pNO3 data is allocated.")
    pNH4 = sr['pNH4'].get_orthogonal_selection(([0], ppl, slice(None)))
    print("pNH4 data is allocated.")
    pSO4 = sr['pSO4'].get_orthogonal_selection(([0], ppl, slice(None)))
    print("pSO4 data is allocated.")
    PM25 = sr['PrimaryPM25'].get_orthogonal_selection(([0], ppl, slice(None)))
    print("PrimaryPM25 data is allocated.")
    if is_BC:
        BCV1 = sr['PrimaryPM25'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("BCV1 data is allocated.")
        BCV3 = sr['PrimaryPM25'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("BCV3 data is allocated.")

    SOA_data, pNO3_data, pNH4_data, pSO4_data, PM25_data = 0.0, 0.0, 0.0, 0.0, 0.0
    BCV1_data, BCV3_data, NO2_data = 0.0, 0.0, 0.0

    print('emis', emis)
    print('index', index)

    for i in range(len(index)):
        SOA_data += SOA[0, dictionary[index[i]], :] * emis.tons_per_year_ROG[i]
        pNO3_data += pNO3[0, dictionary[index[i]], :] * emis.tons_per_year_NOx[i]
        pNH4_data += pNH4[0, dictionary[index[i]], :] * emis.tons_per_year_NH3[i]
        pSO4_data += pSO4[0, dictionary[index[i]], :] * emis.tons_per_year_SOx[i]
        PM25_data += PM25[0, dictionary[index[i]], :] * emis.tons_per_year_PM2_5[i]
        if is_BC:
            BCV1_data += BCV1[0, dictionary[index[i]], :] * emis.tons_per_year_BCV1[i]
            BCV3_data += BCV3[0, dictionary[index[i]], :] * emis.tons_per_year_BCV3[i]
        if is_NO2:
            NO2_data += NO2[0, dictionary[index[i]], :] * emis.tons_per_year_NOx[i]

    data = SOA_data + pNO3_data + pNH4_data + pSO4_data + PM25_data

    fact = 28766.639
    TotalPM25 = fact * data
    #     deathsK = (np.exp(np.log(1.06) / 10 * TotalPM25) - 1) * TotalPop * 1.0465819687408728 * MortalityRate / 100000 * 1.025229357798165
    #     deathsL = (np.exp(np.log(1.14) / 10 * TotalPM25) - 1) * TotalPop * 1.0465819687408728 * MortalityRate / 100000 * 1.025229357798165#
    # Update form 2016 to 2018
    #     1.0465819687408728 is the ratio between year-2016 population (what we want) and year-2010 population (what the model has). 2018 is  1.096163
    #     1.025229357798165 is the ratio between year-2016 mortality rate (what we want) and year-2005 mortality rate (what the model has). 2018 is 0.960899254
    deathsK = (np.exp(np.log(1.06) / 10 * TotalPM25) - 1) * TotalPop * 1.096163 * MortalityRate / 100000 * 0.960899254
    deathsL = (np.exp(np.log(1.14) / 10 * TotalPM25) - 1) * TotalPop * 1.096163 * MortalityRate / 100000 * 0.960899254

    data = {
        'SOA': fact * SOA_data,
        'pNO3': fact * pNO3_data,
        'pNH4': fact * pNH4_data,
        'pSO4': fact * pSO4_data,
        'PrimaryPM25': fact * PM25_data,
        'TotalPM25': TotalPM25,
        'deathsK': deathsK,
        'deathsL': deathsL
    }

    if is_BC:
        data.update({'BCV1': BCV1_data, 'BCV3': BCV3_data})

    if is_NO2:
        data.update({'NO2': NO2_data})

    resultsISRM = gpd.GeoDataFrame(pd.DataFrame(data), geometry=p[0:52411])

    deaths = pd.DataFrame.from_dict({
        "Model": ["ISRM"],
        "Krewski Deaths": [resultsISRM.deathsK.sum()],
        "LePeule Deaths": [resultsISRM.deathsL.sum()],
    })
    print(deaths)
    vsl = 9.0e6
    print(pd.DataFrame.from_dict({
        "Model": ["ISRM"],
        "Krewski Damages": deaths["Krewski Deaths"] * vsl,
        "LePeule Damages": deaths["LePeule Deaths"] * vsl,
    }))

    return resultsISRM


def plot_emis(emis, scenario, scenario2, emissionType, detail_net, is_zoom=False, poll='PM2_5'):
    print('plot_emis ...')
    emis = emis.to_crs(epsg=3857)
    detail_net = detail_net.to_crs(epsg=3857)
    emis['area'] = emis.geometry.area
    emis[f'tons_per_year_{poll}/area_square_meters'] = emis[f'tons_per_year_{poll}'] / emis['area'] * 2589988.11
    emis.to_file(f'{scenario2}_{scenario}_{emissionType}_delta_emis.shp')

    fig, ax = plt.subplots(figsize=(15, 10))

    detail_net.plot(ax=ax, color='grey', alpha=0.05)

    ctx.add_basemap(ax, crs=emis.crs.to_string(), source=ctx.providers.OpenStreetMap.Mapnik, alpha=0.65)
    cmap = custom_colormap()

    emis[(emis[f'tons_per_year_{poll}/area_square_meters'] > 0.001) | (
                emis[f'tons_per_year_{poll}/area_square_meters'] < -0.001)].plot(
        ax=ax, column=f'tons_per_year_{poll}/area_square_meters', cmap=cmap, legend=True,
        legend_kwds={'label': f"Δ{poll} Delta Emission (Tons per Year per Square Mile)", 'orientation': "vertical"},
        vmin=-max(abs(emis[f'tons_per_year_{poll}/area_square_meters'])),
        vmax=max(abs(emis[f'tons_per_year_{poll}/area_square_meters'])),
        #         vmin=-0.2,vmax=0.2,
        alpha=0.65
    )

    if is_zoom:

        ax.set_xlim(-13642750, -13592000)
        ax.set_ylim(4527000, 4565000)

    else:

        ax.set_xlim(-13662750, -13552000)
        ax.set_ylim(4465000, 4585000)

    scalebar = ScaleBar(1, location='lower right', box_color='white', box_alpha=1, color='black', scale_loc='top')
    ax.add_artist(scalebar)
    north_arrow = FancyArrowPatch((0.1, 0.85), (0.1, 0.95), facecolor='black', edgecolor='black',
                                  transform=ax.transAxes, arrowstyle='-|>', mutation_scale=20)
    ax.add_patch(north_arrow)
    ax.text(0.1, 0.95, 'N', transform=ax.transAxes, fontsize=20, ha='center', va='bottom')
    ax.axis('off')
    plt.savefig(f'{scenario2}_{scenario}_{emissionType}_{is_zoom}_{poll}EmissionMap.png', dpi=600)
    plt.show()


def plot_concentrations(resultsISRM, label, scenario, scenario2, emissionType, proj_string, detail_net, is_zoom=False,
                        vmin=-99, vmax=-99):
    print('plot_concentrations ...')
    try:
        resultsISRM = resultsISRM.set_crs(crs=proj_string)
    except:
        None

    if vmin == -99:
        vmin = min(resultsISRM[label])
    if vmax == -99:
        vmax = max(resultsISRM[label])

    resultsISRM = resultsISRM.to_crs(epsg=3857)
    detail_net = detail_net.to_crs(epsg=3857)

    fig, ax = plt.subplots(figsize=(15, 10))
    detail_net.plot(ax=ax, color='grey', alpha=0.05)

    ctx.add_basemap(ax, crs=resultsISRM.crs.to_string(), source=ctx.providers.OpenStreetMap.Mapnik, alpha=0.65)
    cmap = custom_colormap()

    if label == 'TotalPM25':
        legend_kwds = {'label': "ΔPM$_{2.5}$ concentration (μg m$^{-3}$)", 'orientation': "vertical"}
    else:
        legend_kwds = {'label': f"{label} concentration (μg m$^{-3}$)", 'orientation': "vertical"}

    resultsISRM[(resultsISRM[label] > 0.005) | (resultsISRM[label] < -0.005)].plot(
        ax=ax, column=label, cmap=cmap, legend=True,
        legend_kwds=legend_kwds,
        #         vmin=-max(abs(resultsISRM['TotalPM25'])), vmax=max(abs(resultsISRM['TotalPM25'])), alpha=0.65
        vmin=vmin, vmax=vmax,
        alpha=0.65
    )

    if is_zoom:

        ax.set_xlim(-13642750, -13592000)
        ax.set_ylim(4527000, 4565000)

    else:

        ax.set_xlim(-13662750, -13552000)
        ax.set_ylim(4465000, 4585000)

    scalebar = ScaleBar(1, location='lower right', box_color='white', box_alpha=1, color='black', scale_loc='top')
    ax.add_artist(scalebar)
    north_arrow = FancyArrowPatch((0.1, 0.85), (0.1, 0.95), facecolor='black', edgecolor='black',
                                  transform=ax.transAxes, arrowstyle='-|>', mutation_scale=20)
    ax.add_patch(north_arrow)
    ax.text(0.1, 0.95, 'N', transform=ax.transAxes, fontsize=20, ha='center', va='bottom')
    ax.axis('off')
    plt.savefig(f'{scenario2}_{scenario}_{emissionType}_{is_zoom}_{label}.png', dpi=600)
    plt.show()


for scenario, scenario2 in zip(scenarios, scenarios2):
    print('####SCENARIO#####')
    print(f'####{scenario}#####')
    print(f'####{scenario2}#####')

    emis_filepath = f'../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_{scenario}.csv'
    emis_filepath2 = f'../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_{scenario2}.csv'

    emis = load_emis_data(emis_filepath, emis_filepath2)
    shape_data = load_shape_data(emis_shapefile_filepath)
    emis = merge_emis_with_shape(emis, shape_data)
    emis.to_csv(f'{scenario2}_{scenario}_{emissionType}_deltaEmis.csv')

    detail_net = gpd.read_file(
        '/Users/cpoliziani/Downloads/toUse/InMAP/BEAM_to_EMFACT/sfbay-unclassified-unsimplified-unprojected.osm.shp')
    url = 's3://inmap-model/isrm_v1.2.1.zarr/'
    sr, p = load_inmap_data(url)
    resultsISRM = process_emission_data(emis, sr, p)
    resultsISRM.to_file(f'{scenario2}_{scenario}_{emissionType}_resultsISRM.shp')
    resultsISRM.to_csv(f'{scenario2}_{scenario}_{emissionType}_resultsISRM.csv')
    plot_emis(emis, scenario, scenario2, emissionType, detail_net, poll='PM2_5')
    plot_emis(emis, scenario, scenario2, emissionType, detail_net, poll='CO2')
    plot_concentrations(resultsISRM, 'TotalPM25', scenario, scenario2, emissionType, proj_string, detail_net)
    plot_concentrations(resultsISRM, 'NO2', scenario, scenario2, emissionType, proj_string, detail_net, vmin=-0.3,
                        vmax=0.3)
    #     plot_concentrations(resultsISRM,'BCV1', scenario, scenario2, emissionType, proj_string, detail_net)
    #     plot_concentrations(resultsISRM,'BCV3', scenario, scenario2, emissionType, proj_string, detail_net)
    plot_emis(emis, scenario, scenario2, emissionType, detail_net, is_zoom=True, poll='PM2_5')
    plot_emis(emis, scenario, scenario2, emissionType, detail_net, is_zoom=True, poll='CO2')
    plot_concentrations(resultsISRM, 'TotalPM25', scenario, scenario2, emissionType, proj_string, detail_net,
                        is_zoom=True)
    plot_concentrations(resultsISRM, 'NO2', scenario, scenario2, emissionType, proj_string, detail_net, is_zoom=True)
#     plot_concentrations(resultsISRM,'BCV1', scenario, scenario2, emissionType, proj_string, detail_net, is_zoom = True)
#     plot_concentrations(resultsISRM,'BCV3', scenario, scenario2, emissionType, proj_string, detail_net, is_zoom = True)

