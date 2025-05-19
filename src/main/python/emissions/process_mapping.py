import os
import pandas as pd
pd.set_option('display.max_columns', 100)
import re
import matplotlib.pyplot as plt
import seaborn as sns
import numpy as np
from matplotlib.ticker import FuncFormatter

# Create a new column with the extracted class
def extract_class(vehicle_class):
    if vehicle_class == 'T7IS':
        return 'Class 8'
    match = re.search(r'Class (\d+)', vehicle_class)
    if match:
        return f'Class {match.group(1)}'
    return vehicle_class


def plot_vehicle_category_analysis(data, scenario, plot_dir, x_column='vehicleCategory', stack_column=None,
                                   y_column='count', x_order=None, width_size=14, height_size=8,
                                   font_size=12):
    """
    Generate an elegant bar plot for vehicle category analysis.

    Parameters:
    -----------
    data : pandas DataFrame
        DataFrame containing the data to be plotted
    scenario : str
        Name of the scenario being analyzed (used in plot title and filename)
    plot_dir : str
        Directory where plot will be saved
    x_column : str
        Column name to use for x-axis categories (default: 'vehicleCategory')
    stack_column : str or None
        Column name to use for stacking/color groups. If None, a simple bar chart is created.
    y_column : str
        Column name to use for y-axis values (default: 'count')
    x_order : list, optional
        Custom ordering for x-axis categories. If None, categories will be sorted by total value.
    width_size : int or float
        Width of the plot in inches
    height_size : int or float
        Height of the plot in inches
    font_size : int
        Base font size for plot text elements

    Returns:
    --------
    None (saves plot to plot_dir)
    """
    # Ensure plot directory exists
    import os
    import matplotlib.pyplot as plt
    import seaborn as sns
    from matplotlib.ticker import FuncFormatter
    import pandas as pd

    os.makedirs(plot_dir, exist_ok=True)

    # Set clean style with no grid
    plt.style.use('seaborn-v0_8-white')

    # Create figure with clean white background
    fig, ax = plt.subplots(figsize=(width_size, height_size))
    fig.patch.set_facecolor('white')
    ax.set_facecolor('white')

    # Prepare data for the plot
    if stack_column:
        # Stacked bar chart if stack_column is provided
        count_pivot = data.pivot_table(
            index=x_column,
            columns=stack_column,
            values=y_column,
            aggfunc='sum'
        ).fillna(0)

        # Define a better color palette for stacking groups
        unique_stack_values = data[stack_column].unique()
        color_palette = sns.color_palette("viridis", len(unique_stack_values))
        stack_color_map = dict(zip(unique_stack_values, color_palette))
    else:
        # Simple bar chart if no stack_column
        count_pivot = data.groupby(x_column)[y_column].sum().to_frame()

    # Apply custom category ordering if provided
    if x_order:
        # Filter to only include categories that exist in the data
        valid_categories = [cat for cat in x_order if cat in count_pivot.index]

        # Add any categories from the data that weren't in the provided order (at the end)
        missing_categories = [cat for cat in count_pivot.index if cat not in valid_categories]
        ordered_categories = valid_categories + missing_categories

        # Reindex the pivot table with the custom order
        count_pivot = count_pivot.reindex(ordered_categories)
    else:
        # Default: sort by total value
        if stack_column:
            sorted_index = count_pivot.sum(axis=1).sort_values(ascending=False).index
        else:
            sorted_index = count_pivot[y_column].sort_values(ascending=False).index
        count_pivot = count_pivot.loc[sorted_index]

    # Save data to CSV with numbered categories
    csv_data = data.copy()
    category_mapping = {cat: f"{i + 1}. {cat}" for i, cat in enumerate(count_pivot.index)}
    csv_data['numbered_category'] = csv_data[x_column].map(category_mapping)
    csv_filename = f'{plot_dir}/{x_column}_analysis_{scenario.replace(" ", "_").lower()}.csv'
    csv_data.to_csv(csv_filename, index=False)

    # Create numbered categories for the plot
    count_pivot_numbered = count_pivot.copy()
    count_pivot_numbered.index = [f"{i + 1}. {cat}" for i, cat in enumerate(count_pivot.index)]

    # Plot the bars
    if stack_column:
        # Stacked bar chart
        count_pivot_numbered.plot(
            kind='bar',
            stacked=True,
            ax=ax,
            color=[stack_color_map[col] for col in count_pivot_numbered.columns],
            width=0.7,
            edgecolor='white',
            linewidth=0.5
        )
        title = f'{y_column.capitalize()} by {x_column} and {stack_column} - {scenario}'
    else:
        # Simple bar chart
        count_pivot_numbered.plot(
            kind='bar',
            ax=ax,
            color=sns.color_palette("viridis", 1),
            width=0.7,
            edgecolor='white',
            linewidth=0.5
        )
        title = f'{y_column.capitalize()} by {x_column} - {scenario}'

    # Set title and labels
    ax.set_title(title, fontsize=font_size + 4, fontweight='bold', pad=20)
    ax.set_xlabel(x_column.replace('_', ' ').capitalize(), fontsize=font_size + 2, fontweight='bold', labelpad=10)
    ax.set_ylabel(y_column.replace('_', ' ').capitalize(), fontsize=font_size + 2, fontweight='bold', labelpad=10)

    # Format tick labels
    ax.tick_params(axis='x', rotation=0, labelsize=font_size, pad=5)
    ax.tick_params(axis='y', labelsize=font_size, pad=5)
    ax.get_yaxis().set_major_formatter(FuncFormatter(lambda x, loc: "{:,}".format(int(x))))

    # Add subtle spines only at the bottom and left
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    ax.spines['bottom'].set_linewidth(0.5)
    ax.spines['left'].set_linewidth(0.5)

    # Add subtle horizontal lines for y-axis reference
    ax.yaxis.grid(True, linestyle='--', linewidth=0.5, alpha=0.3)
    ax.set_axisbelow(True)

    # Add total values on top of each bar
    if stack_column:
        for i, total in enumerate(count_pivot_numbered.sum(axis=1)):
            ax.text(i, total + (total * 0.01), f'{int(total):,}',
                    ha='center', va='bottom', fontsize=font_size - 1,
                    fontweight='bold', color='#333333')
    else:
        for i, value in enumerate(count_pivot_numbered[y_column]):
            ax.text(i, value + (value * 0.01), f'{int(value):,}',
                    ha='center', va='bottom', fontsize=font_size - 1,
                    fontweight='bold', color='#333333')

    # Create legend if using stack_column
    if stack_column:
        legend = ax.legend(
            title=stack_column.replace('_', ' ').capitalize(),
            bbox_to_anchor=(1.01, 1),
            loc='upper left',
            fontsize=font_size - 1,
            frameon=True,
            framealpha=0.95,
            edgecolor='lightgray'
        )
        plt.setp(legend.get_title(), fontsize=font_size, fontweight='bold')

    # Add subtle border
    for spine in ax.spines.values():
        spine.set_edgecolor('#dddddd')

    # Adjust layout
    plt.tight_layout()

    # Save the plot
    if stack_column:
        filename = f'{plot_dir}/{x_column}_{stack_column}_analysis_{scenario.replace(" ", "_").lower()}.png'
    else:
        filename = f'{plot_dir}/{x_column}_analysis_{scenario.replace(" ", "_").lower()}.png'

    plt.savefig(filename, dpi=300, bbox_inches='tight', facecolor='white')

    plt.show()
    print(f"Plot and data saved to {plot_dir}")


def main():
    # Files
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")
    output_dir = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/emissions-output"
    freight_types_file = f"{work_dir}/vehicle-tech/vehicleTypes--frism--2018-Baseline--EM.csv"
    passenger_types_file = f"{work_dir}/vehicle-tech/vehicleTypes--atlas--2017-Baseline--EM.csv"
    freight_fleet_file = f"{work_dir}/beam-ft/20240123/2018-Baseline/carriers--2018-Baseline--EM.csv"
    passenger_fleet_file = f"{work_dir}/beam-pax/vehicles--atlas--2017-Baseline--EM.csv.gz"
    emfac_fleet_file = f"{work_dir}/emissions/20240123/sfbay_emfac_population_2018-Baseline.csv"

    freight_types = pd.read_csv(freight_types_file)
    passenger_types = pd.read_csv(passenger_types_file)
    passenger_fleet = pd.read_csv(passenger_fleet_file)
    freight_fleet = pd.read_csv(freight_fleet_file)
    emfac_fleet = pd.read_csv(emfac_fleet_file)

    freight_types[['emfacId', 'oldVehicleTypeId']] = freight_types['vehicleTypeId'].str.split('--', expand=True)
    freight_types_emfac = pd.merge(freight_types, emfac_fleet, on="emfacId", how="left")
    passenger_types[['emfacId', 'atlastId', 'routeeId']] = passenger_types['vehicleTypeId'].str.split('--', expand=True)
    passenger_types_emfac = pd.merge(passenger_types, emfac_fleet, on="emfacId", how="left")


    passenger_fleet_count = passenger_fleet.groupby("vehicleTypeId").size().reset_index(name="count")
    freight_fleet_count = freight_fleet.groupby("vehicleTypeId").size().reset_index(name="count")

    freight_count = pd.merge(freight_types_emfac, freight_fleet_count, on="vehicleTypeId", how="left")
    passenger_count = pd.merge(passenger_types_emfac, passenger_fleet_count, on="vehicleTypeId", how="left")

    freight_summary = freight_count.groupby(["fuel", "vehicleCategory", 'vehicle_class', 'model_year_group']).agg({
        "population": "sum",
        "count": "sum"
    }).reset_index()

    passenger_summary = passenger_count.groupby(["fuel", "vehicleCategory", 'vehicle_class', 'model_year_group_y']).agg({
        "population": "sum",
        "count": "sum"
    }).reset_index()
    passenger_summary.rename(columns={'model_year_group_y': 'model_year_group'}, inplace=True)

    freight_summary["demand"] = "freight"
    passenger_summary["demand"] = "passenger"

    fleet_summary = pd.concat([freight_summary, passenger_summary])
    fleet_summary['vehicle_class'] = fleet_summary['vehicle_class'].apply(extract_class)
    fleet_class = fleet_summary.groupby(['vehicleCategory', 'vehicle_class', "demand"]).agg({
        "population": "sum",
        "count": "sum"
    }).reset_index()
    fleet_class["count"] = fleet_class["count"] * 10

    freight = fleet_class[fleet_class["demand"]=="freight"].copy()
    plot_vehicle_category_analysis(
        data=freight,
        scenario='Baseline 2018',
        plot_dir=output_dir,
        x_order=["Class456Vocational", "Class78Vocational", "Class78Tractor"],
        stack_column="vehicle_class",
        width_size=10,
        height_size=6,
        font_size=14
    )
    freight_test = freight.groupby(['vehicleCategory']).agg({
        "population": "sum",
        "count": "sum"
    }).reset_index()
    tot_pop = freight_test["population"].sum()
    tot_count = freight_test["count"].sum()
    freight_test["population_portion"] = freight_test["population"]/tot_pop
    freight_test["count_portion"] = freight_test["count"] / tot_count

    passenger = fleet_summary[fleet_summary["vehicleCategory"] == "Car"].groupby(
        ['fuel','vehicle_class', "demand"]
    ).agg({"population": "sum", "count": "sum"}).reset_index()

    # (data, scenario, plot_dir, x_column='vehicleCategory', stack_column='vehicle_class',
    # y_column1='count', y_column2=None, x_order=None, width_size=14, height_size=8,
    # font_size=12, stacked=True, dodged=True)

    passenger['count'] = passenger['count'] * 10
    plot_vehicle_category_analysis(
        data=passenger,
        scenario='Baseline 2018',
        plot_dir=output_dir,
        x_column='vehicle_class',
        stack_column="fuel",
        y_column='count',
        x_order=["LDA", "LDT1", "LDT2", "MDV"],
        width_size=10,
        height_size=6,
        font_size=14
    )
    passenger_test = passenger.groupby(['vehicle_class']).agg({
        "population": "sum",
        "count": "sum"
    }).reset_index()
    tot_pop = passenger_test["population"].sum()
    tot_count = passenger_test["count"].sum()
    passenger_test["population_portion"] = passenger_test["population"]/tot_pop
    passenger_test["count_portion"] = passenger_test["count"] / tot_count







    print("")



if __name__ == "__main__":
    main()