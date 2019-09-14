import smart_setup_helper

# Main

baseline_2010 = (1, 2010, "base", "Base", "baseline", "sfbay-smart-base-pilates__2019-09-04_05-01-07")
base_2030lt_2010 = (2, 2010, "base", "2030 Low Tech", "base-2030-lt", "sfbay-smart-base-2030-lt-pilates__2019-09-04_05-05-50")
base_2030ht_2010 = (3, 2010, "base", "2030 High Tech", "base-2030-ht", "sfbay-smart-base-2030-ht-pilates__2019-09-04_05-06-20")
base_2045lt_2010 = (4, 2010, "base", "2045 Low Tech", "base-2045-lt", "sfbay-smart-base-2045-lt-pilates__2019-09-04_05-08-09")
base_2045ht_2010 = (5, 2010, "base", "2045 High Tech", "base-2045-ht", "sfbay-smart-base-2045-ht-pilates__2019-09-04_05-08-31")
a_lt_2010 = (6, 2010, "a", "Low Tech", "a-lowtech", "sfbay-smart-a-lt-pilates__2019-09-04_05-02-02")
a_ht_2010 = (7, 2010, "a", "High Tech", "a-hightech", "sfbay-smart-a-ht-pilates__2019-09-04_05-02-49")
b_lt_2010 = (8, 2010, "b", "Low Tech", "b-lowtech", "sfbay-smart-b-lt-pilates__2019-09-04_05-03-22")
b_ht_2010 = (9, 2010, "b", "High Tech", "b-hightech", "sfbay-smart-b-ht-pilates__2019-09-04_05-03-50")
c_lt_2010 = (10, 2010, "c", "Low Tech", "c-lowtech", "sfbay-smart-c-lt-pilates__2019-09-04_05-04-40")
c_ht_2010 = (11, 2010, "c", "High Tech", "c-hightech", "sfbay-smart-c-ht-pilates__2019-09-04_05-05-13")


baseline_2025 = (1, 2025, "base", "Base", "baseline", "sfbay-smart-base-pilates__2019-09-05_00-58-24")
base_2030lt_2025 = (2, 2025, "base", "2030 Low Tech", "base-2030-lt", "sfbay-smart-base-2030-lt-pilates__2019-09-05_01-00-39")
base_2030ht_2025 = (3, 2025, "base", "2030 High Tech", "base-2030-ht", "sfbay-smart-base-2030-ht-pilates__2019-09-05_00-50-53")
base_2045lt_2025 = (4, 2025, "base", "2045 Low Tech", "base-2045-lt", "sfbay-smart-base-2045-lt-pilates__2019-09-05_00-44-37")
base_2045ht_2025 = (5, 2025, "base", "2045 High Tech", "base-2045-ht", "sfbay-smart-base-2045-ht-pilates__2019-09-05_01-05-19")
a_lt_2025 = (6, 2025, "a", "Low Tech", "a-lowtech", "sfbay-smart-a-lt-pilates__2019-09-04_21-50-40")
a_ht_2025 = (7, 2025, "a", "High Tech", "a-hightech", "sfbay-smart-a-ht-pilates__2019-09-04_21-40-46")
b_lt_2025 = (8, 2025, "b", "Low Tech", "b-lowtech", "sfbay-smart-b-lt-pilates__2019-09-05_07-12-53")
# b_ht_2025 = (9, 2025, "b", "High Tech", "b-hightech", "")
c_lt_2025 = (10, 2025, "c", "Low Tech", "c-lowtech", "sfbay-smart-c-lt-pilates__2019-09-05_00-10-56")
c_ht_2025 = (11, 2025, "c", "High Tech", "c-hightech", "sfbay-smart-c-ht-pilates__2019-09-05_01-35-30")

scenarios_2010 = [baseline_2010, base_2030lt_2010, base_2030ht_2010, base_2045lt_2010, base_2045ht_2010,
                  a_lt_2010, a_ht_2010, b_lt_2010, b_ht_2010, c_lt_2010, c_ht_2010]
scenarios_2025 = [baseline_2025, base_2030lt_2025, base_2030ht_2025, base_2045lt_2025, base_2045ht_2025,
                  a_lt_2025, a_ht_2025, b_lt_2025, c_lt_2025, c_ht_2025]
# scenarios = scenarios_2010 + scenarios_2025

setup_config_dict = {
    "base_url": "https://beam-outputs.s3.amazonaws.com/pilates4thSep2019",
    "run_name": "pilates4thSep2019",
    "home_dir": "/Users/haitam/workspace/pyscripts/data/smart",
    "iterations": [15],
    "scenarios": scenarios_2010
}


smart_setup_helper.make_plots(setup_config_dict)


print("END")
