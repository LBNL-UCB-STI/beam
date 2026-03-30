"""
ISRM Concentration Processor

This module processes emission data using the Intervention Model for Air Pollution (InMAP) Reduced-Form 
Source-Receptor Matrix (ISRM) to calculate changes in air pollutant concentrations and related health impacts
across different scenarios.

The tool can:
1. Load and process emissions data from different scenarios
2. Merge emissions data with ISRM polygon data
3. Calculate differences in emissions between scenarios
4. Process emission data through ISRM to get concentration results
5. Generate visualizations of emissions and concentrations
"""

import contextily as ctx
import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sys, os, zarr, s3fs, pyreadr
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import FancyArrowPatch
from matplotlib_scalebar.scalebar import ScaleBar
from shapely.geometry import Polygon

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name


class ISRMConcentrationProcessor:
    """
    A class to process emissions data and calculate concentrations using ISRM.

    This processor takes emissions data from different scenarios, processes it through
    the ISRM model, and calculates resulting concentrations and health impacts.
    """

    def __init__(self, emission_type='All', is_bc=False, is_no2=True):
        """
        Initialize the processor with configuration parameters.

        Args:
            emission_type (str): Type of emissions to process ('onNetwork', 'offNetwork', 'All')
            is_bc (bool): Whether to process Black Carbon (BC) data
            is_no2 (bool): Whether to process NO2 data
        """
        self.emission_type = emission_type
        self.is_bc = is_bc
        self.is_no2 = is_no2
        self.proj_string = "+proj=lcc +lat_0=40 +lon_0=-97 +lat_1=33 +lat_2=45 +x_0=0 +y_0=0 +ellps=sphere +units=m +no_defs +type=crs"

    def create_custom_colormap(self, reverse=False):
        """
        Create a custom colormap with blue-white-red gradient.

        Args:
            reverse (bool): Whether to reverse the color order (red-white-blue)

        Returns:
            matplotlib.colors.LinearSegmentedColormap: The custom colormap
        """
        if reverse:
            colors = ["#ff0000", "#ffffff", "#0000ff"]
        else:
            colors = ["#0000ff", "#ffffff", "#ff0000"]

        n_bins = 100  # Discretizes the interpolation into bins
        cmap_name = 'custom_cmap'
        return LinearSegmentedColormap.from_list(cmap_name, colors, N=n_bins)

    def load_emis_data(self, emis_filepath, emis_filepath2=None):
        """
        Load emissions data from CSV file(s) and process based on emission type.

        If two filepaths are provided, calculates the difference between them.

        Args:
            emis_filepath (str): Path to first emissions CSV file
            emis_filepath2 (str, optional): Path to second emissions CSV file to compare

        Returns:
            pandas.DataFrame: Processed emissions data
        """
        print('Loading emissions data...')
        emis = pd.read_csv(emis_filepath, nrows=None)

        if self.emission_type == 'onNetwork':
            self._process_onnetwork_emissions(emis)
        elif self.emission_type == 'offNetwork':
            self._process_offnetwork_emissions(emis)

        emis = emis[['ISRM', 'tons_per_year_ROG', 'tons_per_year_NOx', 'tons_per_year_NH3',
                     'tons_per_year_SOx', 'tons_per_year_PM2_5', 'tons_per_year_CO2']]
        emis = emis.groupby('ISRM').sum().reset_index()

        if emis_filepath2 and emis_filepath2 != emis_filepath:
            emis2 = pd.read_csv(emis_filepath2, nrows=None)

            if self.emission_type == 'onNetwork':
                self._process_onnetwork_emissions(emis2)
            elif self.emission_type == 'offNetwork':
                self._process_offnetwork_emissions(emis2)

            emis2 = emis2[['ISRM', 'tons_per_year_ROG', 'tons_per_year_NOx', 'tons_per_year_NH3',
                           'tons_per_year_SOx', 'tons_per_year_PM2_5', 'tons_per_year_CO2']]
            emis2 = emis2.groupby('ISRM').sum().reset_index()

            merged_emis = pd.merge(emis, emis2, on='ISRM', how='outer', suffixes=('_1', '_2')).fillna(0)

            # Calculate differences between the two datasets
            for pollutant in ['ROG', 'NOx', 'NH3', 'SOx', 'PM2_5', 'CO2']:
                merged_emis[f'tons_per_year_{pollutant}'] = (
                        merged_emis[f'tons_per_year_{pollutant}_2'] -
                        merged_emis[f'tons_per_year_{pollutant}_1']
                )

            self.calculate_emissions_differences(emis, emis2)
            return merged_emis
        else:
            return emis

    def _process_onnetwork_emissions(self, emis):
        """
        Process on-network emissions data.

        Args:
            emis (pandas.DataFrame): Emissions data to process
        """
        emis['tons_per_year_ROG'] = emis['tons_per_year_RUNEX_ROG'] + emis['tons_per_year_RUNLOSS_ROG']
        emis['tons_per_year_NOx'] = emis['tons_per_year_RUNEX_NOx']
        emis['tons_per_year_NH3'] = emis['tons_per_year_RUNEX_NH3']
        emis['tons_per_year_SOx'] = emis['tons_per_year_RUNEX_SOx']
        emis['tons_per_year_PM2_5'] = (
                emis['tons_per_year_RUNEX_PM2_5'] +
                emis['tons_per_year_PMBW_PM2_5'] +
                emis['tons_per_year_PMTW_PM2_5']
        )
        emis['tons_per_year_CO2'] = emis['tons_per_year_RUNEX_CO2']

    def _process_offnetwork_emissions(self, emis):
        """
        Process off-network emissions data.

        Args:
            emis (pandas.DataFrame): Emissions data to process
        """
        emis['tons_per_year_ROG'] = (
                emis['tons_per_year_DIURN_ROG'] +
                emis['tons_per_year_HOTSOAK_ROG'] +
                emis['tons_per_year_STREX_ROG']
        )
        emis['tons_per_year_NOx'] = emis['tons_per_year_STREX_NOx']
        emis['tons_per_year_NH3'] = 0.0
        emis['tons_per_year_SOx'] = emis['tons_per_year_STREX_SOx']
        emis['tons_per_year_PM2_5'] = emis['tons_per_year_STREX_PM2_5']
        emis['tons_per_year_CO2'] = emis['tons_per_year_STREX_CO2']

    def load_shape_data(self, shapefile_path):
        """
        Load ISRM polygon shape data from shapefile.

        Args:
            shapefile_path (str): Path to the ISRM polygon shapefile

        Returns:
            geopandas.GeoDataFrame: The loaded shapefile data
        """
        print('Loading shape data...')
        return gpd.read_file(shapefile_path)

    def calculate_emissions_differences(self, emis1, emis2):
        """
        Calculate percentage changes in emissions between two scenarios
        for different geographic regions (cordon zone, SF, and rest).

        Args:
            emis1 (pandas.DataFrame): First emissions dataset
            emis2 (pandas.DataFrame): Second emissions dataset
        """
        print('Calculating emissions differences...')

        def calculate_change(old, new):
            """Calculate percentage change"""
            return (new / old - 1) * 100 if old != 0 else 0

        # Define ISRM cordon zone and SF ranges
        cordon_ranges = [(1346, 1350), (1378, 1382), (1393, 1397), (1402, 1402), (1412, 1415)]
        sf_ranges = [
            (983, 986), (988, 991), (1002, 1005), (1039, 1048),
            (1064, 1073), (1084, 1093), (1129, 1138), (1176, 1185),
            (1221, 1230), (1253, 1264), (1291, 1302), (1340, 1345),
            (1351, 1351), (1372, 1377), (1383, 1383), (1388, 1392),
            (1407, 1411), (1416, 1416), (1053, 1053), (1113, 1113),
            (1193, 1193)
        ]

        # Functions to check if ISRM is in specific zones
        def is_in_cordon(isrm):
            return any(start <= isrm <= end for start, end in cordon_ranges)

        def is_in_sf(isrm):
            return any(start <= isrm <= end for start, end in sf_ranges)

        # Filter emissions data for different zones
        emis1_cordon = emis1[emis1['ISRM'].apply(is_in_cordon)]
        emis2_cordon = emis2[emis2['ISRM'].apply(is_in_cordon)]

        emis1_sf = emis1[emis1['ISRM'].apply(is_in_sf)]
        emis2_sf = emis2[emis2['ISRM'].apply(is_in_sf)]

        emis1_rest = emis1[~emis1['ISRM'].apply(lambda x: is_in_cordon(x) or is_in_sf(x))]
        emis2_rest = emis2[~emis2['ISRM'].apply(lambda x: is_in_cordon(x) or is_in_sf(x))]

        # Calculate sum of emissions for each zone
        def calculate_totals(emis):
            return {
                'ROG': emis['tons_per_year_ROG'].sum(),
                'NOx': emis['tons_per_year_NOx'].sum(),
                'NH3': emis['tons_per_year_NH3'].sum(),
                'SOx': emis['tons_per_year_SOx'].sum(),
                'PM2.5': emis['tons_per_year_PM2_5'].sum(),
                'CO2': emis['tons_per_year_CO2'].sum(),
            }

        totals_cordon = calculate_totals(emis1_cordon)
        totals_cordon2 = calculate_totals(emis2_cordon)

        totals_sf = calculate_totals(emis1_sf)
        totals_sf2 = calculate_totals(emis2_sf)

        totals_rest = calculate_totals(emis1_rest)
        totals_rest2 = calculate_totals(emis2_rest)

        # Calculate percentage changes
        def calculate_percentage_changes(totals1, totals2):
            return {key: calculate_change(totals1[key], totals2[key]) for key in totals1}

        delta_cordon = calculate_percentage_changes(totals_cordon, totals_cordon2)
        delta_sf = calculate_percentage_changes(totals_sf, totals_sf2)
        delta_rest = calculate_percentage_changes(totals_rest, totals_rest2)

        # Print the results
        print("\n--- Total Emissions ---")
        self._print_emissions_results("Cordon Zone", totals_cordon)
        self._print_emissions_results("San Francisco (SF)", totals_sf)
        self._print_emissions_results("Rest of the Area", totals_rest)

        print("\n--- Percentage Change in Emissions ---")
        self._print_emissions_results("Cordon Zone", delta_cordon, is_percent=True)
        self._print_emissions_results("San Francisco (SF)", delta_sf, is_percent=True)
        self._print_emissions_results("Rest of the Area", delta_rest, is_percent=True)

    def _print_emissions_results(self, zone_name, data, is_percent=False):
        """
        Helper method to print emissions results.

        Args:
            zone_name (str): Name of the zone
            data (dict): Emissions data to print
            is_percent (bool): Whether the data represents percentages
        """
        print(f"{zone_name}:")
        for pollutant, value in data.items():
            if is_percent:
                print(f"  {pollutant}: {value:.2f}% change")
            else:
                print(f"  {pollutant}: {value:.2f} tons/year")

    def merge_emis_with_shape(self, emis_data, shape_data):
        """
        Merge emissions data with shape data to create a GeoDataFrame.

        Args:
            emis_data (pandas.DataFrame): Emissions data
            shape_data (geopandas.GeoDataFrame): Shape data with geometries

        Returns:
            geopandas.GeoDataFrame: Merged emissions and shape data
        """
        print('Merging emissions with shape data...')
        emis_data['ISRM'] = emis_data['ISRM'].astype(str).str.upper()
        shape_data['isrm'] = shape_data['isrm'].astype(str).str.upper()

        merged_data = emis_data.merge(shape_data[['isrm', 'geometry']],
                                      left_on='ISRM', right_on='isrm')

        gdf = gpd.GeoDataFrame(merged_data, geometry='geometry')
        gdf['ISRM'] = gdf['ISRM'].astype(int)
        gdf['area'] = gdf.geometry.area

        return gdf[['ISRM', 'tons_per_year_ROG', 'tons_per_year_NOx', 'tons_per_year_NH3',
                    'tons_per_year_SOx', 'tons_per_year_PM2_5', 'tons_per_year_CO2', 'geometry']]

    def load_inmap_data(self, url):
        """
        Load InMAP data from S3 and create polygons.

        Args:
            url (str): S3 URL for the InMAP data

        Returns:
            tuple: (Zarr store with InMAP data, list of polygons)
        """
        print('Loading InMAP data...')
        fs = s3fs.S3FileSystem(anon=True, client_kwargs=dict(region_name='us-east-2'))
        sr = zarr.open(s3fs.S3Map(url, s3=fs, check=False), mode="r")
        polygons = self._create_polygons(sr)
        return sr, polygons

    def _create_polygons(self, sr):
        """
        Create polygon geometries from InMAP grid data.

        Args:
            sr: Zarr store with InMAP grid data

        Returns:
            list: List of Shapely Polygon objects
        """

        def rect(i, w, s, e, n):
            """Create rectangle coordinates"""
            x = [w[i], e[i], e[i], w[i], w[i]]
            y = [s[i], s[i], n[i], n[i], s[i]]
            return x, y

        polygons = []
        w = sr["W"][:]
        s = sr["S"][:]
        e = sr["E"][:]
        n = sr["N"][:]

        for i in range(52411):
            x, y = rect(i, w, s, e, n)
            polygons.append(Polygon([
                [x[0], y[0]], [x[1], y[1]], [x[2], y[2]],
                [x[3], y[3]], [x[4], y[4]]
            ]))

        return polygons

    def process_emission_data(self, emis, sr, polygons):
        """
        Process emission data through ISRM to calculate concentrations and health impacts.

        Args:
            emis (geopandas.GeoDataFrame): Emissions data with geometries
            sr: Zarr store with InMAP data
            polygons (list): List of polygons for the InMAP grid

        Returns:
            geopandas.GeoDataFrame: Processed results with concentrations and health impacts
        """
        print('Processing emission data through ISRM...')

        # Load population and mortality data
        total_pop = sr['TotalPop'][0:52411]
        mortality_rate = sr['MortalityRate'][0:52411]

        # Create location mapping
        df = pd.DataFrame({'Location': range(52411)})
        df['Location'] = df['Location'].astype(int)

        emis['ISRM'] = emis['ISRM'].astype(int)
        join_right_df = df.merge(emis, left_on='Location', right_on='ISRM', how='right')

        index = join_right_df.Location.tolist()
        ppl = np.unique(join_right_df.Location.tolist())
        num = range(0, len(ppl))
        dictionary = dict(zip(ppl, num))

        print(f"Available data keys: {list(sr.keys())}")

        # Load NO2 conversion data if needed
        no2_data = None
        if self.is_no2:
            no2_data = self._load_no2_conversion_data(ppl)

        # Load InMAP data for different pollutants
        soa = sr['SOA'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("SOA data loaded. Shape:", soa.shape)

        pno3 = sr['pNO3'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("pNO3 data loaded.")

        pnh4 = sr['pNH4'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("pNH4 data loaded.")

        pso4 = sr['pSO4'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("pSO4 data loaded.")

        pm25 = sr['PrimaryPM25'].get_orthogonal_selection(([0], ppl, slice(None)))
        print("PrimaryPM25 data loaded.")

        # Initialize BC variables only if needed
        bcv1 = None
        bcv3 = None
        if self.is_bc:
            # Check if BCV1 and BCV3 fields exist in emis
            if 'tons_per_year_BCV1' in emis.columns and 'tons_per_year_BCV3' in emis.columns:
                bcv1 = sr['PrimaryPM25'].get_orthogonal_selection(([0], ppl, slice(None)))
                print("BCV1 data loaded.")

                bcv3 = sr['PrimaryPM25'].get_orthogonal_selection(([0], ppl, slice(None)))
                print("BCV3 data loaded.")
            else:
                print("Warning: BC processing enabled but BCV1/BCV3 columns not found in emissions data")
                self.is_bc = False

        # Initialize concentration data arrays
        soa_data = np.zeros(52411)
        pno3_data = np.zeros(52411)
        pnh4_data = np.zeros(52411)
        pso4_data = np.zeros(52411)
        pm25_data = np.zeros(52411)

        bcv1_data = np.zeros(52411) if self.is_bc else 0.0
        bcv3_data = np.zeros(52411) if self.is_bc else 0.0
        no2_data = np.zeros(52411) if self.is_no2 else 0.0

        # Process emissions through ISRM
        for i in range(len(index)):
            isrm_idx = dictionary[index[i]]

            soa_data += soa[0, isrm_idx, :] * emis.tons_per_year_ROG.iloc[i]
            pno3_data += pno3[0, isrm_idx, :] * emis.tons_per_year_NOx.iloc[i]
            pnh4_data += pnh4[0, isrm_idx, :] * emis.tons_per_year_NH3.iloc[i]
            pso4_data += pso4[0, isrm_idx, :] * emis.tons_per_year_SOx.iloc[i]
            pm25_data += pm25[0, isrm_idx, :] * emis.tons_per_year_PM2_5.iloc[i]

            if self.is_bc and bcv1 is not None and bcv3 is not None:
                bcv1_data += bcv1[0, isrm_idx, :] * emis.tons_per_year_BCV1.iloc[i]
                bcv3_data += bcv3[0, isrm_idx, :] * emis.tons_per_year_BCV3.iloc[i]

            if self.is_no2 and no2_data is not None:
                no2_data += no2_data[0, isrm_idx, :] * emis.tons_per_year_NOx.iloc[i]

        # Calculate total PM2.5 and health impacts
        total_data = soa_data + pno3_data + pnh4_data + pso4_data + pm25_data
        fact = 28766.639  # Conversion factor

        total_pm25 = fact * total_data

        # Population and mortality rate adjustments
        pop_adjustment = 1.096163  # Ratio between 2016 and 2010 population
        mortality_adjustment = 0.960899254  # Ratio between 2016 and 2005 mortality rates

        # Calculate health impacts using concentration-response functions
        deaths_k = (
                (np.exp(np.log(1.06) / 10 * total_pm25) - 1) *
                total_pop * pop_adjustment *
                mortality_rate / 100000 * mortality_adjustment
        )

        deaths_l = (
                (np.exp(np.log(1.14) / 10 * total_pm25) - 1) *
                total_pop * pop_adjustment *
                mortality_rate / 100000 * mortality_adjustment
        )

        # Prepare results data
        data = {
            'SOA': fact * soa_data,
            'pNO3': fact * pno3_data,
            'pNH4': fact * pnh4_data,
            'pSO4': fact * pso4_data,
            'PrimaryPM25': fact * pm25_data,
            'TotalPM25': total_pm25,
            'deathsK': deaths_k,
            'deathsL': deaths_l
        }

        if self.is_bc:
            data.update({'BCV1': bcv1_data, 'BCV3': bcv3_data})

        if self.is_no2:
            data.update({'NO2': no2_data})

        # Create GeoDataFrame with results
        results_isrm = gpd.GeoDataFrame(pd.DataFrame(data), geometry=polygons[0:52411])

        # Calculate and print total health impacts
        total_deaths = pd.DataFrame.from_dict({
            "Model": ["ISRM"],
            "Krewski Deaths": [results_isrm.deathsK.sum()],
            "LePeule Deaths": [results_isrm.deathsL.sum()],
        })

        print(total_deaths)

        # Calculate monetary valuation using Value of Statistical Life (VSL)
        vsl = 9.0e6  # Value of Statistical Life in USD
        damages = pd.DataFrame.from_dict({
            "Model": ["ISRM"],
            "Krewski Damages": total_deaths["Krewski Deaths"] * vsl,
            "LePeule Damages": total_deaths["LePeule Deaths"] * vsl,
        })

        print(damages)

        return results_isrm


    def _load_no2_conversion_data(self, ppl):
        """
        Load NO2 conversion data from R data file.

        Args:
            ppl (numpy.array): Array of locations to process

        Returns:
            numpy.array: NO2 conversion matrix
        """
        result = pyreadr.read_r('NOx_to_NO2_ISRM.RData')
        no2_df = result['res.dat']

        # Convert indices and columns to integers
        no2_df.index = no2_df.index.astype(int)
        no2_df.columns = no2_df.columns.astype(int)

        # Create full domain and fill with zeros
        full_index = list(range(52411))
        no2_full = no2_df.reindex(index=full_index, columns=full_index, fill_value=0.0)

        # Subset rows to locations of interest
        no2_subset = no2_full.loc[ppl, :]

        # Convert to numpy array and add time dimension
        no2 = no2_subset.values
        no2 = no2[np.newaxis, :, :]

        print('NO2 conversion data loaded. Shape:', no2.shape)

        return no2

    def plot_emissions(self, emis, scenario1, scenario2, detail_net, is_zoom=False, pollutant='PM2_5'):
        """
        Plot emissions data on a map.

        Args:
            emis (geopandas.GeoDataFrame): Emissions data with geometries
            scenario1 (str): First scenario name
            scenario2 (str): Second scenario name
            detail_net (geopandas.GeoDataFrame): Network data for background
            is_zoom (bool): Whether to zoom into a specific area
            pollutant (str): Pollutant to plot
        """
        print(f'Plotting emissions map for {pollutant}...')

        # Convert to web mercator projection for basemap
        emis = emis.to_crs(epsg=3857)
        detail_net = detail_net.to_crs(epsg=3857)

        # Calculate emissions per unit area
        emis['area'] = emis.geometry.area
        emis[f'tons_per_year_{pollutant}/area_square_meters'] = (
                emis[f'tons_per_year_{pollutant}'] / emis['area'] * 2589988.11  # Convert to per square mile
        )

        # Save to shapefile
        emis.to_file(f'{scenario2}_{scenario1}_{self.emission_type}_delta_emis.shp')

        # Create figure and plot
        fig, ax = plt.subplots(figsize=(15, 10))

        # Add network as background
        detail_net.plot(ax=ax, color='grey', alpha=0.05)

        # Add OpenStreetMap basemap
        ctx.add_basemap(ax, crs=emis.crs.to_string(), source=ctx.providers.OpenStreetMap.Mapnik, alpha=0.65)

        # Create custom colormap
        cmap = self.create_custom_colormap()

        # Filter and plot emissions that exceed threshold
        filtered_emis = emis[(emis[f'tons_per_year_{pollutant}/area_square_meters'] > 0.001) |
                             (emis[f'tons_per_year_{pollutant}/area_square_meters'] < -0.001)]

        # Determine color scale
        vmax = max(abs(emis[f'tons_per_year_{pollutant}/area_square_meters']))

        # Plot emissions data
        filtered_emis.plot(
            ax=ax,
            column=f'tons_per_year_{pollutant}/area_square_meters',
            cmap=cmap,
            legend=True,
            legend_kwds={
                'label': f"Δ{pollutant} Delta Emission (Tons per Year per Square Mile)",
                'orientation': "vertical"
            },
            vmin=-vmax,
            vmax=vmax,
            alpha=0.65
        )

        # Set map extent
        if is_zoom:
            ax.set_xlim(-13642750, -13592000)
            ax.set_ylim(4527000, 4565000)
        else:
            ax.set_xlim(-13662750, -13552000)
            ax.set_ylim(4465000, 4585000)

        # Add scale bar and north arrow
        self._add_map_elements(ax)

        # Save figure and display
        plt.savefig(f'{scenario2}_{scenario1}_{self.emission_type}_{is_zoom}_{pollutant}EmissionMap.png', dpi=600)
        plt.show()

    def plot_concentrations(self, results, label, scenario1, scenario2, detail_net,
                            is_zoom=False, vmin=None, vmax=None):
        """
        Plot concentration results on a map.

        Args:
            results (geopandas.GeoDataFrame): Concentration results
            label (str): Data column to plot
            scenario1 (str): First scenario name
            scenario2 (str): Second scenario name
            detail_net (geopandas.GeoDataFrame): Network data for background
            is_zoom (bool): Whether to zoom into a specific area
            vmin (float, optional): Minimum value for color scale
            vmax (float, optional): Maximum value for color scale
        """
        print(f'Plotting concentration map for {label}...')

        # Set coordinate reference system if not already set
        try:
            results = results.set_crs(crs=self.proj_string)
        except:
            pass  # Already has CRS

        # Set color scale limits if not provided
        if vmin is None:
            vmin = -max(abs(results[label]))
        if vmax is None:
            vmax = max(abs(results[label]))

        # Convert to web mercator projection for basemap
        results = results.to_crs(epsg=3857)
        detail_net = detail_net.to_crs(epsg=3857)

        # Create figure and plot
        fig, ax = plt.subplots(figsize=(15, 10))

        # Add network as background
        detail_net.plot(ax=ax, color='grey', alpha=0.05)

        # Add OpenStreetMap basemap
        ctx.add_basemap(ax, crs=results.crs.to_string(), source=ctx.providers.OpenStreetMap.Mapnik, alpha=0.65)

        # Create custom colormap
        cmap = self.create_custom_colormap()

        # Set appropriate legend label
        if label == 'TotalPM25':
            legend_kwds = {'label': "ΔPM$_{2.5}$ concentration (μg m$^{-3}$)", 'orientation': "vertical"}
        else:
            legend_kwds = {'label': f"Δ{label} concentration (μg m$^{-3}$)", 'orientation': "vertical"}

        # Filter and plot results that exceed threshold
        filtered_results = results[(results[label] > 0.005) | (results[label] < -0.005)]

        filtered_results.plot(
            ax=ax,
            column=label,
            cmap=cmap,
            legend=True,
            legend_kwds=legend_kwds,
            vmin=vmin,
            vmax=vmax,
            alpha=0.65
        )

        # Set map extent
        if is_zoom:
            ax.set_xlim(-13642750, -13592000)
            ax.set_ylim(4527000, 4565000)
        else:
            ax.set_xlim(-13662750, -13552000)
            ax.set_ylim(4465000, 4585000)

        # Add scale bar and north arrow
        self._add_map_elements(ax)

        # Save figure and display
        plt.savefig(f'{scenario2}_{scenario1}_{self.emission_type}_{is_zoom}_{label}.png', dpi=600)
        plt.show()

    def _add_map_elements(self, ax):
        """
        Add common map elements (scale bar, north arrow).

        Args:
            ax (matplotlib.axes.Axes): The axes to add elements to
        """
        # Add scale bar
        scalebar = ScaleBar(1, location='lower right', box_color='white',
                            box_alpha=1, color='black', scale_loc='top')
        ax.add_artist(scalebar)

        # Add north arrow
        north_arrow = FancyArrowPatch(
            (0.1, 0.85), (0.1, 0.95),
            facecolor='black',
            edgecolor='black',
            transform=ax.transAxes,
            arrowstyle='-|>',
            mutation_scale=20
        )
        ax.add_patch(north_arrow)

        # Add 'N' label
        ax.text(0.1, 0.95, 'N', transform=ax.transAxes, fontsize=20, ha='center', va='bottom')

        # Remove axis
        ax.axis('off')

    def process_scenario(self, scenario1, scenario2, emis_filepath1, emis_filepath2,
                         shapefile_path, detail_net_path, inmap_url):
        """
        Process a pair of scenarios to calculate and visualize emission differences and concentrations.

        Args:
            scenario1 (str): First scenario name
            scenario2 (str): Second scenario name
            emis_filepath1 (str): Path to first emissions CSV file
            emis_filepath2 (str): Path to second emissions CSV file
            shapefile_path (str): Path to ISRM polygon shapefile
            detail_net_path (str): Path to network GeoJSON/shapefile
            inmap_url (str): S3 URL for InMAP data

        Returns:
            tuple: (Emissions GeoDataFrame, Concentration results GeoDataFrame)
        """
        print(f'\n=== Processing Scenarios: {scenario1} vs {scenario2} ===\n')

        # Load and process emissions data
        emis = self.load_emis_data(emis_filepath1, emis_filepath2)

        # Load shape data and merge with emissions
        shape_data = self.load_shape_data(shapefile_path)
        emis = self.merge_emis_with_shape(emis, shape_data)

        # Save emissions data
        emis.to_csv(f'{scenario2}_{scenario1}_{self.emission_type}_deltaEmis.csv')

        # Load detail network for visualization
        detail_net = gpd.read_file(detail_net_path)

        # Load InMAP data
        sr, polygons = self.load_inmap_data(inmap_url)

        # Process emissions through ISRM
        results = self.process_emission_data(emis, sr, polygons)

        # Save results
        results.to_file(f'{scenario2}_{scenario1}_{self.emission_type}_resultsISRM.shp')
        results.to_csv(f'{scenario2}_{scenario1}_{self.emission_type}_resultsISRM.csv')

        # Create visualizations
        # Emissions maps
        self.plot_emissions(emis, scenario1, scenario2, detail_net, pollutant='PM2_5')
        self.plot_emissions(emis, scenario1, scenario2, detail_net, pollutant='CO2')

        # Zoomed emissions maps
        self.plot_emissions(emis, scenario1, scenario2, detail_net, is_zoom=True, pollutant='PM2_5')
        self.plot_emissions(emis, scenario1, scenario2, detail_net, is_zoom=True, pollutant='CO2')

        # Concentration maps
        self.plot_concentrations(results, 'TotalPM25', scenario1, scenario2, detail_net)

        if self.is_no2:
            self.plot_concentrations(results, 'NO2', scenario1, scenario2, detail_net, vmin=-0.3, vmax=0.3)

        # Zoomed concentration maps
        self.plot_concentrations(results, 'TotalPM25', scenario1, scenario2, detail_net, is_zoom=True)

        if self.is_no2:
            self.plot_concentrations(results, 'NO2', scenario1, scenario2, detail_net, is_zoom=True,
                                     vmin=-0.3, vmax=0.3)

        return emis, results

def main():
    """
    Main function to run the ISRM concentration processing for multiple scenarios.
    """
    area = "sfbay"
    run_batch = "20240123"
    scenario = "2018-Baseline"
    config = get_area_config(area)
    work_dir = config["work_dir"]
    network_name = generate_network_name(config)
    config["emissions"][scenario]["run"]["output_dir"] = f"emissions/{run_batch}"
    inmap_conf = config["air-quality"]["inmap"]
    inmap_conf["isrm_grid"] = f"inmap/ISRM/isrm_polygon.shp"
    inmap_conf["beam-mapping"] = f"inmap/{network_name}/isrm-beam--network-intersection.geojson"
    inmap_conf["run"]["output_dir"] = f"inmap/{run_batch}"

    # Configuration
    emis_shapefile_filepath = f'{work_dir}/{inmap_conf["isrm_grid"]}'
    emission_type = 'All'  # Options: 'onNetwork', 'offNetwork', 'All'
    detail_net_path = f'{work_dir}/{inmap_conf["beam-mapping"]}'
    inmap_url = 's3://inmap-model/isrm_v1.2.1.zarr/'

    # Initialize processor
    processor = ISRMConcentrationProcessor(emission_type=emission_type, is_bc=False, is_no2=True)

    # Define scenarios to process
    scenario_pairs = [
        ('sfbay-baseline3_20240728', 'sfbay-cordon_income_20241023'),
    ]

    # File paths for each scenario
    scenario_filepaths = {
        'sfbay-baseline3_20240728': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-baseline3_20240728.csv',
        'sfbay-cordon_income_20241023': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-cordon_income_20241023.csv',
        'sfbay-tr_capacity_1_5-20230608': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-tr_capacity_1_5-20230608.csv',
        'sfbay-wb-incentives-200-20230630': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-wb-incentives-200-20230630.csv',
        'sfbay-tr-discount-100-20230703': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-tr-discount-100-20230703.csv',
        'sfbay-telecommuting-baseline-20230616': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-telecommuting-baseline-20230616.csv',
        'sfbay-telecommuting-8p60-20230620': '../BEAM_to_EMFACT/BEAM_INMAP_detail_ISRM_sfbay-telecommuting-8p60-20230620.csv'
    }

    # Process each scenario pair
    for scenario1, scenario2 in scenario_pairs:
        emis_filepath1 = scenario_filepaths[scenario1]
        emis_filepath2 = scenario_filepaths[scenario2]

        processor.process_scenario(
            scenario1,
            scenario2,
            emis_filepath1,
            emis_filepath2,
            emis_shapefile_filepath,
            detail_net_path,
            inmap_url
        )

if __name__ == "__main__":
    main()