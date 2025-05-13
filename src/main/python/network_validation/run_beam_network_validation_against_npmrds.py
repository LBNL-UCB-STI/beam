from validation_utils import *
from pathlib import Path
import sys
import os

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name


def run_validation(study_area, paths, run_link_speed_validation=True,
                   run_network_speed_validation=True, run_vmt_validation=False):
    """Run the validation processes based on the flags."""

    # Initialize validation setup
    setup = SpeedValidationSetup(
        npmrds_hourly_speed_csv=paths["npmrds_hourly_speed_csv"],
        npmrds_hourly_speed_by_road_class_csv=paths["npmrds_hourly_speed_by_road_class_csv"],
        beam_network_mapped_to_npmrds_geo=paths["beam_network_mapped_to_npmrds_geo"]
    )

    link_stats = [LinkStats(scenario=paths["scenario_label"], demand_fraction=0.1, file_path=paths["link_stats"])]

    # Process link stats if any validation is needed
    if run_link_speed_validation or run_network_speed_validation or run_vmt_validation:
        print("Run: " + str(link_stats))
        processed_link_stats = setup.process_these_link_stats(link_stats=link_stats, assume_daylight_saving=True)
    else:
        processed_link_stats = None

    # Network-level speed validation
    if run_network_speed_validation:
        run_network_speed_validation_process(study_area, setup, processed_link_stats, paths)

    # Link-level speed validation
    if run_link_speed_validation:
        run_link_speed_validation_process(study_area, setup, processed_link_stats, paths)

    # VMT validation (commented out in original)
    # if run_vmt_validation:
    #     run_vmt_validation_process(study_area, vehicle_types_files, paths)

    print("END")


def run_network_speed_validation_process(study_area, setup, processed_link_stats, paths):
    """Run network-level speed validation."""
    hourly_speed_by_road_class = setup.get_hourly_average_speed_by_road_class(processed_link_stats)
    hourly_speed_by_road_class_no_npmrds = hourly_speed_by_road_class[
        ~hourly_speed_by_road_class['scenario'].str.contains("npmrds", case=False, na=False)
    ]

    # Plot hourly network speed by road class
    plt.figure()
    g = sns.relplot(x='hour', y='speed', hue='road_class', col='scenario', kind="line",
                    data=hourly_speed_by_road_class_no_npmrds,
                    errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True})
    g.set_titles("{col_name}")
    g.fig.suptitle('Network-level Speed Validation by Road Class', fontsize=16, y=0.98)
    g.set_xlabels("Hour")
    g.set_ylabels("Speed (mph)")
    g.legend.set_title("Road Category")
    plt.subplots_adjust(top=0.85)
    plt.ylim([0, 70])
    plt.savefig(paths["plots_dir"] + '/' + study_area + '_beam_npmrds_network_speed_road_class_validation.png', dpi=200)
    plt.show(block=False)

    hourly_speed_by_road_class_no_npmrds.to_csv(
        paths["output_dir"] + '/' + study_area + '_beam_npmrds_network_speed_road_class_validation.csv', index=False)


def run_link_speed_validation_process(study_area, setup, processed_link_stats, paths):
    """Run link-level speed validation."""
    hourly_link_speed = setup.get_hourly_link_speed(processed_link_stats)

    # Plot hourly link speed
    plt.figure()
    sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_link_speed, errorbar=('ci', 95))
    plt.ylim([0, 70])
    plt.title("Link-level Speed Validation")
    plt.savefig(paths["plots_dir"] + '/' + study_area + '_beam_npmrds_link_speed_validation.png', dpi=200)
    plt.show(block=False)

    hourly_link_speed_by_road_class = setup.get_hourly_link_speed_by_road_class(processed_link_stats)

    # Plot hourly link speed by road class
    plt.figure()
    road_class_order = list(fsystem_to_roadclass_lookup.values())
    g = sns.relplot(x='hour', y='speed', hue='road_class', col='scenario', kind="line", hue_order=road_class_order,
                    data=hourly_link_speed_by_road_class,
                    errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True})
    g.set_titles("{col_name}")
    g.fig.suptitle('Link-Level Speed Validation by Road Class', fontsize=16, y=0.98)
    g.set_xlabels("Hour")
    g.set_ylabels("Speed (mph)")
    g.legend.set_title("Road Category")
    plt.subplots_adjust(top=0.85)
    plt.ylim([0, 70])
    plt.savefig(paths["plots_dir"] + '/' + study_area + '_beam_npmrds_link_speed_road_class_validation.png', dpi=200)
    plt.show(block=False)

    hourly_link_speed_by_road_class.to_csv(
        paths["output_dir"] + '/' + study_area + '_beam_npmrds_link_speed_road_class_validation.csv', index=False)


# Commented out as it was commented in the original
# def run_vmt_validation_process(study_area, vehicle_types_files, paths):
#     """Run VMT validation."""
#     pts = pd.DataFrame()
#     # Read vehicle types
#     for (batch, scenario, events_file, veh_types_file) in vehicle_types_files:
#         # Read and process freight events
#         run = read_events(
#             events_file,
#             veh_types_file,
#             batch,
#             scenario
#         )
#         pt = get_ft_path_traversals(run)
#         pts = pd.concat([pts, pt])
#
#     # Process baseline data
#     baseline_summary, baseline_summary_levels, baseline_summary_colors = process_ft_path_traversals(baseline_runs,
#                                                                                                    baseline_runs_name,
#                                                                                                    baseline_output_dir)
#
#     # Validate VMT
#     validation = validate_vmt(baseline_summary, WORK_DIR)
#
#     # Create plots
#     plot_results(
#         baseline_summary,
#         validation,
#         baseline_summary_colors,
#         baseline_output_dir,
#         "2024-08-07"
#     )


def main():
    """Main function to run the validation."""
    study_area_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")

    # Validation flags
    run_link_speed_validation = True
    run_network_speed_validation = True
    run_vmt_validation = False

    # Configuration settings
    study_area = "sfbay"  # or "seattle"
    batch = "20240123"
    scenario = "2018-Baseline"
    run_dir = f"{study_area_dir}/beam-runs/{batch}/{scenario}"
    paths = {
        "study_area_dir": study_area_dir,
        "run_dir": run_dir,
        "scenario_label": f"{batch}--{scenario}",
        "link_stats": f"{run_dir}/3.linkstats.csv.gz",
        "output_dir": f"{run_dir}/validation_output",
        "plots_dir": f"{run_dir}/validation_output/plots",
        "npmrds_hourly_speed_csv": f"{run_dir}/{study_area}_npmrds_hourly_speeds.csv",
        "npmrds_hourly_speed_by_road_class_csv":  f"{run_dir}/{study_area}_npmrds_hourly_speed_by_road_class.csv",
        "beam_network_mapped_to_npmrds_geo": f"{run_dir}/{study_area}_network_mapped_to_npmrds.geojson"
    }

    # Create directories if they don't exist
    Path(paths["plots_dir"]).mkdir(parents=True, exist_ok=True)

    # Run validation
    run_validation(
        study_area=study_area,
        paths=paths,
        run_link_speed_validation=run_link_speed_validation,
        run_network_speed_validation=run_network_speed_validation,
        run_vmt_validation=run_vmt_validation
    )


if __name__ == "__main__":
    main()