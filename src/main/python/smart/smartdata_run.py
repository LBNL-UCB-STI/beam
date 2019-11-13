import smartdata_setup

def createUrl(foldername):
    return "https://beam-outputs.s3.amazonaws.com/output/sfbay/"+foldername

# Main
baseline_2010 = (1, 2010, 15, "base", "Base", "baseline", createUrl("sfbay-smart-base-2010__2019-10-28_20-14-32"))

base_2030lt_2025 = (2, 2025, 15, "base", "2030 Low Tech", "base_fleet_2030_lt", createUrl("sfbay-smart-base-2030-lt-2025__2019-10-29_22-54-03"))
base_2030ht_2025 = (3, 2025, 15, "base", "2030 High Tech", "base_fleet_2030_ht", createUrl("sfbay-smart-base-2030-ht-2025__2019-10-29_22-54-02"))
base_2045lt_2040 = (4, 2040, 15, "base", "2045 Low Tech", "base_fleet_2045_lt", createUrl("sfbay-smart-base-2045-lt-2040__2019-10-29_22-54-02"))
base_2045ht_2040 = (5, 2040, 15, "base", "2045 High Tech", "base_fleet_2045_ht", createUrl("sfbay-smart-base-2045-ht-2040__2019-10-29_22-54-19"))

a_lt_2025 = (6, 2025, 15, "a", "Low Tech", "a_lt", createUrl("sfbay-smart-a-lt-2025__2019-10-28_20-14-33"))
a_ht_2025 = (7, 2025, 15, "a", "High Tech", "a_ht", createUrl("sfbay-smart-a-ht-2025__2019-10-28_20-14-32"))
b_lt_2040 = (8, 2040, 15, "b", "Low Tech", "b_lt", createUrl("sfbay-smart-b-lt-2040__2019-10-28_20-15-46"))
b_ht_2040 = (9, 2040, 15, "b", "High Tech", "b_ht", createUrl("sfbay-smart-b-ht-2040__2019-10-28_20-17-54"))
c_lt_2040 = (10, 2040, 15, "c", "Low Tech", "c_lt", createUrl("sfbay-smart-c-lt-2040__2019-10-28_20-18-25"))
c_ht_2040 = (11, 2040, 15, "c", "High Tech", "c_ht", createUrl("sfbay-smart-c-ht-2040__2019-10-28_20-18-30"))

scenarios_28_10_2019 = [baseline_2010,
                        base_2030lt_2025, base_2030ht_2025, base_2045lt_2040, base_2045ht_2040,
                        a_lt_2025, a_ht_2025, b_lt_2040, b_ht_2040, c_lt_2040, c_ht_2040]

setup_config_dict = {
    "run_name": "28thOct2019",
    "home_dir": "/home/ubuntu/git/jupyter/data",
    "scenarios": scenarios_28_10_2019
}

smartdata_setup.make_plots(setup_config_dict)

print("END")