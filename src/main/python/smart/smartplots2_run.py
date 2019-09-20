import pandas as pd
import smartplots2_setup

plt_setup_smart2 = {
    'expansion_factor': (7.75/0.315) * 27.0 / 21.3,

    'iteration': 15,
    'scenarios_id': [1, 6, 7, 8, 9, 10, 11],
    'scenarios_year': [2010, 2025, 2025, 2025, 2025, 2040, 2040],

    'plot_size': (5, 4.5),
    'bottom_labels': ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"],
    'top_labels': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
}

output_folder = "/Users/haitam/workspace/pyscripts/data/smart/15thSep2019"


smartplots2_setup.pltModeSplitByTrips2(plt_setup_smart2, output_folder)