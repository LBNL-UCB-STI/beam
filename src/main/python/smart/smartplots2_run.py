import pandas as pd
import smartplots2_setup

plt_setup_smart2 = {
    'name': 'no-futuristic-base',
    'expansion_factor': (7.75/0.315) * 27.0 / 21.3,

    'scenarios_itr': [15, 15, 15, 15, 15, 15, 15],
    'scenarios_id': [1, 6, 7, 8, 9, 10, 11],
    'scenarios_year': [2010, 2025, 2025, 2025, 2025, 2040, 2040],

    'plot_size': (5, 4.5),
    'bottom_labels': ['Base\n2010', 'Sharing is Caring\n2025', 'Technology Takeover\n2025', "All About Me\n2040"],
    'top_labels': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
}

output_folder = "/Users/haitam/workspace/pyscripts/data/smart/15thSep2019"


smartplots2_setup.pltModeSplitByTrips(plt_setup_smart2, output_folder)
smartplots2_setup.tableSummary(plt_setup_smart2, output_folder)

smartplots2_setup.pltLdvRhOccupancy(plt_setup_smart2, output_folder)
smartplots2_setup.pltModeSplitInPMT(plt_setup_smart2, output_folder)
smartplots2_setup.pltModeSplitInPMTPerCapita(plt_setup_smart2, output_folder)
smartplots2_setup.pltLdvTechnologySplitInVMT(plt_setup_smart2, output_folder)
smartplots2_setup.pltModeSplitInVMT(plt_setup_smart2, output_folder)
smartplots2_setup.pltModeSplitInVMTPerCapita(plt_setup_smart2, output_folder)
smartplots2_setup.pltRHEmptyPooled(plt_setup_smart2, output_folder)
smartplots2_setup.pltLdvRhOccupancyByVMT(plt_setup_smart2, output_folder)


plt_setup_smart2_base = {
    'name': 'with-futuristic-base',
    'expansion_factor': (7.75/0.315) * 27.0 / 21.3,

    'scenarios_itr': [15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15],
    'scenarios_id': [1, 2, 3, 6, 7, 8, 9, 4, 5, 10, 11],
    'scenarios_year': [2010, 2025, 2025, 2025, 2025, 2025, 2025, 2040, 2040, 2040, 2040],

    'plot_size': (7.5, 4.5),
    'bottom_labels': ['Base\n2010', 'Base Short\n2025', 'Sharing is Caring\n2025', 'Technology Takeover\n2025', "Base Long\n2040", "All About Me\n2040"],
    'top_labels': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
}

smartplots2_setup.pltEnergyPerCapita(plt_setup_smart2_base, output_folder)
smartplots2_setup.pltLdvPersonHourTraveled(plt_setup_smart2_base, output_folder)
smartplots2_setup.pltRHWaitTime(plt_setup_smart2_base, output_folder)