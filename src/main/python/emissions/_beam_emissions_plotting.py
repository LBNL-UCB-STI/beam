import gzip
import io
import os
import warnings

import contextily as cx
import geopandas as gpd
import h3
import matplotlib.colors as mcolors
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import seaborn as sns
from matplotlib.colors import LogNorm
from shapely.geometry import Polygon
from tqdm import tqdm
from tqdm.auto import tqdm

# Fuel Color Map
fuel_color_map = {
    'Elec': '#4169E1',  # Royal Blue
    'H2fc': '#6495ED',  # Cornflower Blue
    'Phe': '#87CEEB',  # Sky Blue
    'NG': '#B0E0E6',    # Pale Blue
    'BioDsl': '#98FB98',  # Pale Green
    'Dsl': '#FFD700',  # Gold
    'Gas': '#708090'  # Slate Gray
}

process_color_map = {
    'IDLEX':   '#fde725',  # Light yellow
    'RUNEX':   '#7ad151',  # Light green
    'PMBW':    '#22a884',  # Teal
    'PMTW':    '#2a788e',  # Blue-green
    'STREX': '#8e0152',   # Dark magenta
    'RUNLOSS': '#4b0082',   # Indigo
    'HOTSOAK': '#414487',  # Purple-blue
    'DIURN': '#440154',  # Dark purple
}


def remove_outliers_zscore(df, column, threshold=3):
    mean = df[column].mean()
    std = df[column].std()
    z_scores = np.abs((df[column] - mean) / std)
    df_filtered = df[z_scores < threshold].copy()
    removed_rows = df[~df.index.isin(df_filtered.index)]
    summary_df = pd.DataFrame({
        'column': [column],
        'mean': [mean],
        'std': [std],
        'num_outliers': [len(removed_rows)]
    })
    print(summary_df)
    print(removed_rows)
    return df_filtered

def darken_color(color, factor=0.8):
    rgb = mcolors.to_rgb(color)
    return tuple(max(0, c * factor) for c in rgb)


def plot_hourly_emissions_by_scenario_class_fuel(emissions_skims, pollutant, output_dir, plot_legend, height_size, font_size):
    data = emissions_skims[emissions_skims['pollutant'] == pollutant].copy()
    grouped_data = data.groupby(['scenario', 'hour', 'class', 'emfacFuel'])['rate'].sum().reset_index()

    plt.figure(figsize=(20, height_size))

    grouped_data['fuel_class'] = grouped_data['emfacFuel'].astype(str) + ', ' + grouped_data['class'].astype(str)
    scenarios = grouped_data['scenario'].unique()
    fuel_classes = sorted(grouped_data['fuel_class'].unique())
    all_hours = sorted(grouped_data['hour'].unique())


    # Create color map for fuel_classes
    fuel_class_colors = {}
    for fc in fuel_classes:
        fuel, vehicle_class = fc.split(',')
        fuel = fuel.strip()
        vehicle_class = vehicle_class.strip()
        base_color = fuel_color_map[fuel]  # Default to black if fuel not found
        if any(c in vehicle_class for c in ['7', '8']):
            fuel_class_colors[fc] = darken_color(base_color)
        else:
            fuel_class_colors[fc] = base_color

    x = np.arange(len(all_hours))
    width = 0.35 / len(scenarios)

    scenarios_labeling = []
    for i, scenario in enumerate(scenarios):
        scenarios_labeling.append(scenario)
        scenario_data = grouped_data[grouped_data['scenario'] == scenario]
        bottom = np.zeros(len(all_hours))
        for fuel_class in fuel_classes:
            fuel_class_data = scenario_data[scenario_data['fuel_class'] == fuel_class]
            # Create an array of rates for all hours, filling with zeros where data is missing
            rates = np.zeros(len(all_hours))
            for _, row in fuel_class_data.iterrows():
                hour_index = all_hours.index(row['hour'])
                rates[hour_index] = row['rate']

            # Add edgecolor and linewidth parameters to create a subtle border
            plt.bar(x + i * width, rates, width, bottom=bottom,
                    label=f"{fuel_class}" if i == 0 else "",
                    color=fuel_class_colors[fuel_class],
                    edgecolor='black',  # Add black edge color
                    linewidth=0.5)  # Adjust linewidth as needed
            bottom += rates

    plt.title(
        f'{pollutant.replace("_", ".")} Emissions: {" vs. ".join(scenarios_labeling)}',
        fontsize=font_size+4)
    plt.xlabel('Hour', fontsize=font_size)
    plt.ylabel('Emissions (Metric Tons)', fontsize=font_size)
    plt.xticks(x + width * (len(scenarios) - 1) / 2, all_hours, fontsize=font_size)
    plt.yticks(fontsize=24)
    if plot_legend:
        plt.legend(title='Fuel, Class', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=font_size+4, title_fontsize=font_size+4)
    plt.grid(axis='y', linestyle='--', alpha=0.7)

    plt.tight_layout()
    plt.savefig(f'{output_dir}/{pollutant.lower()}_emissions_by_scenario_hour_class_fuel.png', dpi=300, bbox_inches='tight')


def plot_hourly_activity(tours_types, output_dir, height_size):
    # Preprocess data
    tours_types['fuel_class'] = tours_types['mappedFuel'] + '-' + tours_types['mappedClass']
    tours_types['departure_hour'] = (tours_types['departureTimeInSec'] / 3600).astype(int) % 24
    # Group by scenario, hour, and fuel_class, count the number of tours
    hourly_activity = tours_types.groupby(['scenario', 'departure_hour', 'fuel_class']).size().unstack(
        level=[0, 2], fill_value=0
    )

    scenarios = tours_types['scenario'].unique()
    # If the DataFrame is empty, create a default one with all hours
    if hourly_activity.empty:
        fuel_classes = tours_types['fuel_class'].unique()
        index = pd.Index(range(24), name='departure_hour')
        columns = pd.MultiIndex.from_product([scenarios, fuel_classes], names=['scenario', 'fuel_class'])
        hourly_activity = pd.DataFrame(0, index=index, columns=columns)
    else:
        # Ensure all hours are present
        for hour in range(24):
            if hour not in hourly_activity.index:
                hourly_activity.loc[hour] = 0
        hourly_activity = hourly_activity.sort_index()

    # Create the plot
    plt.figure(figsize=(20, height_size))
    x = np.arange(24)  # 24 hours
    width = 0.35  # width of the bars
    scenarios = hourly_activity.columns.levels[0]

    # Get all unique fuel classes across all scenarios
    all_fuel_classes = set()
    for scenario in scenarios:
        all_fuel_classes.update(hourly_activity[scenario].columns)

    fuel_order = list(fuel_color_map.keys())
    # Sort fuel classes based on the defined order
    sorted_fuel_classes = sorted(all_fuel_classes,
                                 key=lambda x: (
                                 fuel_order.index(x.split('-')[0]) if x.split('-')[0] in fuel_order else len(
                                     fuel_order), x))

    # Create a color map for all fuel types
    #color_map = {fuel: fuel_color_map[fuel] for fuel in fuel_order}
    color_map = {}
    for fc in sorted_fuel_classes:
        fuel, vehicle_class = fc.split('-')
        base_color = fuel_color_map[fuel] # Default to black if fuel not found
        if any(c in vehicle_class for c in ['7', '8']):
            color_map[fc] = darken_color(base_color)
        else:
            color_map[fc] = base_color

    print("Sorted fuel classes:", sorted_fuel_classes)
    print("Color map:", color_map)

    # Plot stacked bars for each scenario
    legend_handles = []
    legend_labels = []
    for i, scenario in enumerate(scenarios):
        bottom = np.zeros(24)
        for fuel_class in sorted_fuel_classes:
            color = color_map[fuel_class]

            if fuel_class in hourly_activity[scenario].columns:
                values = hourly_activity[scenario][fuel_class]
            else:
                values = np.zeros(24)

            bar = plt.bar(x + i * width, values, width, bottom=bottom, color=color, edgecolor='black', linewidth=0.5)
            bottom += values

            if fuel_class not in legend_labels:
                legend_handles.append(bar)
                legend_labels.append(fuel_class)

    # plt.title(f'Weekday Tour Activity by Fuel, Class and Scenario: {" vs ".join(scenarios).replace("_", " ")}', fontsize=24)
    plt.xlabel('Hour', fontsize=24)
    plt.ylabel('Number of Tours Departing', fontsize=24)
    plt.xticks(x + width / 2, range(24), fontsize=24)
    plt.yticks(fontsize=12)

    # Create legend with ordered fuel classes
    plt.legend(legend_handles, legend_labels, fontsize=28, loc='upper left', bbox_to_anchor=(1, 1))

    plt.grid(axis='y', linestyle='--', alpha=0.7)

    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(f'{output_dir}/hourly_activity_by_scenario_fuel_class.png', dpi=300, bbox_inches='tight')
    plt.close()

    print(f"Plot saved as {output_dir}/hourly_activity_by_scenario_fuel_class.png")


def plot_hourly_vmt(df, output_dir, height_size):
    # Preprocess the data
    df['fuel_class'] = df['beamFuel'].astype(str) + '-' + df['class'].astype(str)
    df['hour'] = df['hour'].astype(int) % 24
    df['mvmt'] = df['vmt'] / 1e6

    scenarios = df['scenario'].unique()

    hourly_vmt = df.groupby(['scenario', 'hour', 'fuel_class'])['mvmt'].sum().unstack(
        level=[0, 2], fill_value=0
    ).copy().reset_index()

    # Ensure all hours are present
    for hour in range(24):
        if hour not in hourly_vmt.index:
            hourly_vmt.loc[hour] = 0
    hourly_vmt = hourly_vmt.sort_index()

    # Create the plot
    plt.figure(figsize=(20, height_size))
    x = np.arange(24)  # 24 hours
    width = 0.35  # width of the bars

    # Get all unique fuel classes across all scenarios
    all_fuel_classes = set()
    for scenario in scenarios:
        all_fuel_classes.update(hourly_vmt[scenario].columns)

    fuel_order = list(fuel_color_map.keys())
    # Sort fuel classes based on the defined order
    sorted_fuel_classes = sorted(all_fuel_classes,
                                 key=lambda x: (
                                 fuel_order.index(x.split('-')[0]) if x.split('-')[0] in fuel_order else len(
                                     fuel_order), x))


    # Create color map for fuel_classes
    color_map = {}
    for fc in sorted_fuel_classes:
        fuel, vehicle_class = fc.split('-')
        base_color = fuel_color_map[fuel] # Default to black if fuel not found
        if any(c in vehicle_class for c in ['7', '8']):
            color_map[fc] = darken_color(base_color)
        else:
            color_map[fc] = base_color

    # Plot stacked bars for each scenario
    legend_handles = []
    legend_labels = []
    for i, scenario in enumerate(scenarios):
        bottom = np.zeros(24)
        for fuel_class in sorted_fuel_classes:
            if fuel_class in hourly_vmt[scenario].columns:
                values = hourly_vmt[scenario][fuel_class]
            else:
                values = np.zeros(24)

            bar = plt.bar(x + i * width, values, width, bottom=bottom, color=color_map[fuel_class], edgecolor='black', linewidth=0.5)
            bottom += values

            if fuel_class not in legend_labels:
                legend_handles.append(bar)
                legend_labels.append(fuel_class)

    # plt.title(f'Weekday VMT by Fuel, Class and Scenario: {" vs ".join(scenarios).replace("_", " ")}', fontsize=20)
    plt.xlabel('Hour', fontsize=24)
    plt.ylabel('Million Vehicle Miles Traveled', fontsize=24)
    plt.xticks(x + width / 2, range(24), fontsize=24)
    plt.yticks(fontsize=24)

    # Create legend with ordered fuel classes
    plt.legend(legend_handles, legend_labels, title='Fuel, Class', fontsize=28, loc='upper left', bbox_to_anchor=(1, 1))
    plt.grid(axis='y', linestyle='--', alpha=0.7)

    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(f'{output_dir}/hourly_vmt_by_scenario_fuel_class.png', dpi=300, bbox_inches='tight')
    plt.close()

    print(f"Hourly VMT plot saved as {output_dir}/hourly_vmt_by_scenario_fuel_class.png")


def plot_h3_heatmap(df, df_col, scenario, output_dir, is_delta, remove_outliers, in_log_scale):
    """Create a heatmap using the H3 grid structure with linear or logarithmic color scale and a base map."""
    subset_df = df[df["scenario"] == scenario]
    if remove_outliers:
        subset_df = remove_outliers_zscore(subset_df, df_col)

    # Create polygons for all H3 cells in the result
    polygons = [Polygon(h3.h3_to_geo_boundary(h3_cell, geo_json=True)) for h3_cell in subset_df['h3_cell']]

    # Create GeoDataFrame
    gdf = gpd.GeoDataFrame({
        'h3_cell': subset_df['h3_cell'],
        'h3_var': subset_df[df_col],
        'geometry': polygons
    })
    gdf = gdf.set_crs("EPSG:4326")

    # Convert to Web Mercator projection for compatibility with contextily
    gdf_mercator = gdf.to_crs(epsg=3857)

    # Create figure and axis
    fig, ax = plt.subplots(figsize=(15, 10))

    vmin, vmax = gdf_mercator['h3_var'].min(), gdf_mercator['h3_var'].max()

    if in_log_scale:
        if is_delta:
            norm = mcolors.SymLogNorm(linthresh=1e-5, vmin=vmin, vmax=vmax)
        else:
            gdf_mercator = gdf_mercator[gdf_mercator['h3_var'] > 0]
            vmin, vmax = gdf_mercator['h3_var'].min(), gdf_mercator['h3_var'].max()
            norm = LogNorm(vmin=vmin, vmax=vmax)
        label_suffix = "in log scale"
        file_suffix = "log"
    else:
        if is_delta:
            norm = mcolors.TwoSlopeNorm(vmin=vmin, vcenter=0, vmax=vmax)
        else:
            norm = None
        label_suffix = ""
        file_suffix = "linear"

    # Choose colormap based on whether it's a delta calculation
    if is_delta:
        cmap = mcolors.LinearSegmentedColormap.from_list("", ["blue", "lightblue", "white", "pink", "red"])
    else:
        cmap = plt.get_cmap('viridis')

    # Plot cells with data
    gdf_mercator.plot(column='h3_var', ax=ax, legend=False, cmap=cmap, edgecolor='none', norm=norm, alpha=0.7)

    # Add base map
    cx.add_basemap(ax, source=cx.providers.CartoDB.Positron)

    # Add colorbar
    sm = plt.cm.ScalarMappable(cmap=cmap, norm=norm)
    sm.set_array([])
    if is_delta:
        cbar = fig.colorbar(sm, ax=ax, extend='both')
    else:
        cbar = fig.colorbar(sm, ax=ax, extend='max')

    cbar.ax.tick_params(labelsize=14)
    cbar.set_label(f'{df_col.replace("_", ".")} {label_suffix}', rotation=270, labelpad=15, fontsize=18)

    # Set title and adjust plot
    # plt.title(f'Emissions Distribution of {df_col.replace("_", ".")}, {scenario} ', fontsize=16)
    ax.set_axis_off()
    plt.tight_layout()

    # Save figure
    outlier_status = "no_outliers" if remove_outliers else "with_outliers"
    file_name = f'{output_dir}/{df_col.replace(" ", "_").lower()}_{scenario.replace(" ", "_").lower()}_heatmap_{file_suffix}_{outlier_status}_with_basemap.png'
    plt.savefig(file_name, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Heatmap with base map saved as {file_name}")


def create_h3_histogram(df, output_dir, pollutant, scenario, remove_outliers, in_log_scale):
    subset_df = df[df["scenario"] == scenario]
    if remove_outliers:
        subset_df = remove_outliers_zscore(subset_df, pollutant)
    # Extract pollutant values
    pollutant_values = subset_df[pollutant].values

    # Create the histogram
    plt.figure(figsize=(12, 6))

    if in_log_scale:
        # Use log-spaced bins, but with adjustments for potential zero values
        bins = np.logspace(np.log10(pollutant_values.min() + 1e-10),
                           np.log10(pollutant_values.max()),
                           num=50)
        x_label = f'{pollutant.replace("_", ".")} Emissions (log scale)'
        title_label = f'Histogram of {pollutant.replace("_", ".")} Emissions by H3 Cell (Log Scale)'
        file_name = f'{output_dir}/{pollutant}_{scenario.replace(" ","_").lower()}_emissions_histogram_log.png'
    else:
        # Use automatic binning based on Sturges' rule
        bins = 'sturges'
        x_label = f'{pollutant.replace("_", ".")} Emissions'
        title_label = f'Histogram of {pollutant.replace("_", ".")} Emissions by H3 Cell'
        file_name = f'{output_dir}/{pollutant}_{scenario.replace(" ","_").lower()}_emissions_histogram.png'

    plt.hist(pollutant_values, bins=bins, edgecolor='black')

    # Set x-axis to log scale if specified
    if in_log_scale:
        plt.xscale('log')

    # Set labels and title
    plt.xlabel(x_label, fontsize=12)
    plt.ylabel('Frequency', fontsize=12)
    plt.title(title_label, fontsize=14)

    # Add grid for better readability
    plt.grid(True, linestyle='--', alpha=0.7)

    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(file_name, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Histogram saved as {file_name}/")


def fast_df_to_gzip(df, output_file, compression_level=5, chunksize=100000):
    """
    Write a pandas DataFrame to a compressed CSV.gz file quickly with a progress bar.

    :param df: pandas DataFrame to write
    :param output_file: path to the output .csv.gz file
    :param compression_level: gzip compression level (1-9, 9 being highest)
    :param chunksize: number of rows to write at a time
    """
    total_rows = len(df)

    with gzip.open(output_file, 'wt', compresslevel=compression_level) as gz_file:
        # Write header
        gz_file.write(','.join(df.columns) + '\n')

        # Write data in chunks
        with tqdm(total=total_rows, desc="Writing to gzip", unit="rows") as pbar:
            for start in range(0, total_rows, chunksize):
                end = min(start + chunksize, total_rows)
                chunk = df.iloc[start:end]

                csv_buffer = io.StringIO()
                chunk.to_csv(csv_buffer, index=False, header=False)
                gz_file.write(csv_buffer.getvalue())

                pbar.update(end - start)


def plot_multi_pie_emfac_famos_vmt(data, plot_dir):
    def assign_color(fuel_class):
        return fuel_color_map[fuel_class.split('-')[0]]

    models = data["model"].unique()

    emfac_data = data[data['model'] == 'emfac'].sort_values('mvmt', ascending=False)
    famos_data = data[data['model'] == 'famos'].sort_values('mvmt', ascending=False)

    all_fuel_classes = set(emfac_data['fuel_class']) | set(famos_data['fuel_class'])
    for fuel_class in all_fuel_classes:
        if fuel_class not in emfac_data['fuel_class'].values:
            emfac_data = pd.concat(
                [emfac_data, pd.DataFrame({'fuel_class': [fuel_class], 'model': ['EMFAC'], 'mvmt': [0]})],
                ignore_index=True)
        if fuel_class not in famos_data['fuel_class'].values:
            famos_data = pd.concat(
                [famos_data, pd.DataFrame({'fuel_class': [fuel_class], 'model': ['FAMOS'], 'mvmt': [0]})],
                ignore_index=True)

    emfac_data = emfac_data.sort_values('fuel_class')
    famos_data = famos_data.sort_values('fuel_class')

    if emfac_data['mvmt'].sum() == 0 and famos_data['mvmt'].sum() == 0:
        print("Error: All VMT values are zero. Cannot create pie chart.")
        return

    fig, ax = plt.subplots(figsize=(14, 10))
    size = 0.3
    outer_radius = 1
    inner_radius = outer_radius - size
    outer_colors = [assign_color(fuel_class) for fuel_class in famos_data['fuel_class']]
    inner_colors = [assign_color(fuel_class) for fuel_class in emfac_data['fuel_class']]

    def make_autopct(values):
        def my_autopct(pct):
            return f'{pct:.1f}%' if pct >= 1 else ''

        return my_autopct

    def add_labels(wedges, fuel_classes, autopct, colors, radius, inner=False):
        for wedge, fuel_class, color in zip(wedges, fuel_classes, colors):
            ang = (wedge.theta2 + wedge.theta1) / 2
            pct = wedge.theta2 - wedge.theta1
            if pct * 100 / 360 >= 1:  # Only show labels for slices >= 1%
                label = autopct(pct * 100 / 360)
                theta = np.deg2rad(ang)

                if inner:
                    start_point = ((inner_radius - size) * np.cos(theta), (inner_radius - size) * np.sin(theta))
                    end_point = (0.4 * np.cos(theta), 0.4 * np.sin(theta))

                    bbox_props = dict(boxstyle="round,pad=0.3", fc=color, ec="k", lw=0.72, alpha=0.7)
                    arrowprops = dict(arrowstyle="-", connectionstyle=f"arc3,rad=0", color='k')

                    ax.annotate(f'{fuel_class}\n{label}', xy=start_point, xytext=end_point,
                                horizontalalignment='center',
                                verticalalignment='center',
                                bbox=bbox_props, arrowprops=arrowprops,
                                fontsize=16)
                else:
                    x = (radius + size / 2 + 0.05) * np.cos(theta)
                    y = (radius + size / 2 + 0.05) * np.sin(theta)

                    bbox_props = dict(boxstyle="round,pad=0.3", fc=color, ec="k", lw=0.72, alpha=0.7)
                    ax.annotate(f'{fuel_class}\n{label}', xy=(x, y), xytext=(x, y),
                                horizontalalignment='center',
                                verticalalignment='center',
                                bbox=bbox_props,
                                fontsize=16)

    wedges_outer, texts_outer, autotexts_outer = ax.pie(famos_data['mvmt'], radius=outer_radius, colors=outer_colors,
                                                        labels=None, autopct='', pctdistance=0.85,
                                                        labeldistance=1.1,
                                                        wedgeprops=dict(width=size, edgecolor='white'))

    add_labels(wedges_outer, famos_data['fuel_class'], make_autopct(famos_data['mvmt']), outer_colors, outer_radius)

    wedges_inner, texts_inner, autotexts_inner = ax.pie(emfac_data['mvmt'], radius=inner_radius, colors=inner_colors,
                                                        labels=None, autopct='', pctdistance=0.75,
                                                        wedgeprops=dict(width=size, edgecolor='white'))

    add_labels(wedges_inner, emfac_data['fuel_class'], make_autopct(emfac_data['mvmt']), inner_colors, inner_radius, inner=True)

    # ax.set_title('VMT Share by Fuel-Class: FAMOS (outer) vs EMFAC (inner)', fontsize=16)

    # handles = [plt.Rectangle((0, 0), 1, 1, fc="w", ec="k", lw=2, alpha=0.5) for _ in range(2)]
    # labels = ['FAMOS (Outer)', 'EMFAC (Inner)']
    # ax.legend(handles, labels, title="Models", loc="center left", bbox_to_anchor=(1, 0, 0.5, 1))

    plt.tight_layout()
    output_file = os.path.join(plot_dir, f"{'_'.join(models)}_vmt_multi_level_pie_chart.png")
    plt.savefig(output_file, bbox_inches='tight', dpi=300)
    plt.close()
    print(f"Chart has been saved as '{output_file}'")


def plot_pollution_variability_by_process_vehicle_types(skims, pollutant, scenario, output_dir, height_size, font_size):
    warnings.filterwarnings("ignore", category=FutureWarning, module="seaborn")
    # Filter data for specified scenario and pollutant
    data = skims[(skims['scenario'] == scenario) & (skims['pollutant'] == pollutant)].copy()
    processes = sorted(skims["process"].unique().tolist())

    # Create fuel_class category
    data['fuel_class'] = data['emfacFuel'].astype(str) + ', ' + data['class'].astype(str)
    data['rate_micro_gram'] = data['rate'] * 1e12

    # Sort fuel_class by median emission rate
    fuel_class_order = data.groupby('fuel_class')['rate_micro_gram'].median().sort_values(ascending=False).index

    # Set up the plot
    fig, ax = plt.subplots(figsize=(20, height_size))

    # Create color map for fuel_classes
    fuel_class_colors = {}
    for fc in data['fuel_class'].unique():
        fuel, vehicle_class = fc.split(',')
        fuel = fuel.strip()
        vehicle_class = vehicle_class.strip()
        base_color = fuel_color_map[fuel]  # Default to black if fuel not found
        if any(c in vehicle_class for c in ['7', '8']):
            fuel_class_colors[fc] = darken_color(base_color)
        else:
            fuel_class_colors[fc] = base_color

    # Create the box plot with adjusted parameters
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=FutureWarning)
        sns.boxplot(x='process', y='rate_micro_gram', hue='fuel_class', data=data,
                    order=processes, hue_order=fuel_class_order,
                    palette=fuel_class_colors,
                    ax=ax, whis=1.5, fliersize=2, showcaps=True, showfliers=True)

        # Add strip plot for additional data points
        sns.stripplot(x='process', y='rate_micro_gram', hue='fuel_class', data=data,
                      order=processes, hue_order=fuel_class_order,
                      palette=fuel_class_colors,
                      ax=ax, size=1, jitter=True, dodge=True, alpha=0.3)

    # Customize the plot
    ax.set_title(f'{pollutant.replace("_", ".")} Emissions Variability - {scenario}', fontsize=font_size+4)
    ax.set_xlabel('Process', fontsize=font_size)
    ax.set_ylabel('Microgram per road link', fontsize=font_size)
    ax.tick_params(axis='both', which='major', labelsize=font_size)

    # Rotate x-axis labels if needed
    plt.setp(ax.get_xticklabels(), rotation=0, ha='right')

    # Move the legend outside the plot
    ax.legend(title='Fuel, Class', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=font_size)

    # Use log scale for y-axis if the range of values is large
    min_rate = data['rate_micro_gram'].min()
    max_rate = data['rate_micro_gram'].max()

    if min_rate <= 0:
        print(f"Warning: Minimum rate is {min_rate}, which is zero or negative. Using log scale by default.")
        ax.set_yscale('log')
        # Set a small positive value for the bottom of the y-axis
        ax.set_ylim(bottom=1e-10)  # You might need to adjust this value
        scale_label = "log"
    elif max_rate / min_rate > 1000:
        print(f"Using log scale. Max/min ratio: {max_rate/min_rate}")
        ax.set_yscale('log')
        scale_label = "log"
    else:
        print(f"Using linear scale. Max/min ratio: {max_rate/min_rate}")
        scale_label = "linear"

    plt.tight_layout()
    plt.savefig(f'{output_dir}/{pollutant.lower()}_variability_by_process_fuel_class_{scenario.replace(" ", "_").lower()}_{scale_label}_scale.png', dpi=300, bbox_inches='tight')
    plt.close()


def plot_pollutants_by_process(skims, scenario, plot_dir, height_size, font_size):
    # Define process order and color map based on toxicity
    process_order = list(process_color_map.keys())
    # Group by pollutant and process, and sum the rates
    grouped = skims[skims["scenario"] == scenario].groupby(['pollutant', 'process'])['rate'].sum().unstack()

    # Reorder columns based on process_order
    grouped = grouped.reindex(columns=process_order)

    # Normalize the data
    normalized = grouped.div(grouped.sum(axis=1), axis=0)
    normalized = normalized * 100

    # Create the stacked bar plot
    fig, ax = plt.subplots(figsize=(20, height_size))
    normalized.plot(kind='bar', stacked=True, ax=ax, color=[process_color_map[col] for col in normalized.columns])

    # Customize the plot
    plt.title(f'Normalized Emissions by Process - {scenario}', fontsize=font_size+4)
    plt.xlabel('Pollutant', fontsize=font_size)
    plt.ylabel('Relative Emissions (%)', fontsize=font_size)
    plt.xticks(rotation=0, ha='center', fontsize=font_size)
    plt.yticks(fontsize=font_size)

    ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda y, _: '{:.0f}%'.format(y)))
    ax.set_ylim(0, 100)

    legend = plt.legend(title='Process', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=font_size)
    plt.setp(legend.get_title(), fontsize=font_size)
    plt.tight_layout()

    # Save the plot
    plt.savefig(
        f'{plot_dir}/pollutant_by_process_{scenario.replace(" ", "_").lower()}.png',
        dpi=300,
        bbox_inches='tight'
    )

    # Show the plot
    plt.show()

