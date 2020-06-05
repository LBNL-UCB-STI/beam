from smartplots_setup import pltModeSplitByTrips
from smartplots_setup import pltEnergyPerCapita
from smartplots_setup import pltLdvRhOccupancy
from smartplots_setup import pltLdvPersonHourTraveled
from smartplots_setup import pltModeSplitInPMT
from smartplots_setup import pltModeSplitInVMT
from smartplots_setup import pltLdvTechnologySplitInVMT
from smartplots_setup import pltRHWaitTime
from smartplots_setup import pltRHEmptyPooled
from smartplots_setup import pltLdvRhOccupancyByVMT
from smartplots_setup import tableSummary
import pandas as pd


plt_setup_base_smart = {
    'expansion_factor': (7.75/0.315) * 27.0 / 21.3,
    'rotation': 13,
    'fig_size': (7.5, 4.5),
    'scenarios': ['Base', 'Base-Short', 'Base-Long', 'Sharing is Caring', 'Technology Takeover', "All About Me"],
    'scenarios_xpos': [1, 3.5, 6.5, 9.5, 12.5, 15.5],
    'technologies': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
    'technologies_xpos': [1, 3, 4, 6, 7, 9, 10, 12, 13, 15, 16],
    'dimension': 11,
    'rank_to_filterout': []
}


plt_setup_smart = {
    'expansion_factor': (7.75/0.315) * 27.0 / 21.3,
    'rotation': 11,
    'fig_size': (5, 4.5),
    'scenarios': ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"],
    'scenarios_xpos': [1, 3.5, 6.5, 9.5],
    'technologies': ["Base", "BAU", "VTO", "BAU", "VTO", "BAU", "VTO"],
    'technologies_xpos': [1, 3, 4, 6, 7, 9, 10],
    'dimension': 7,
    'rank_to_filterout': [2, 3, 4, 5]
}

output_folder = "/Users/haitam/workspace/pyscripts/data/smart/15thSep2019"
year = "2010"
iteration = "15"
prefix = "{}.{}".format(year, iteration)
metrics_file = "{}/{}.metrics-final.csv".format(output_folder, prefix)

df = pd.read_csv(metrics_file).fillna(0)

tableSummary(plt_setup_smart, df, output_folder, prefix)

pltModeSplitByTrips(plt_setup_smart, df, output_folder, prefix)
pltLdvRhOccupancy(plt_setup_smart, df, output_folder, prefix)
pltModeSplitInPMT(plt_setup_smart, df, output_folder, prefix)
pltLdvTechnologySplitInVMT(plt_setup_smart, df, output_folder, prefix)
pltModeSplitInVMT(plt_setup_smart, df, output_folder, prefix)
pltRHEmptyPooled(plt_setup_smart, df, output_folder, prefix)
pltLdvRhOccupancyByVMT(plt_setup_smart, df, output_folder, prefix)

pltEnergyPerCapita(plt_setup_base_smart, df, output_folder, prefix)
pltLdvPersonHourTraveled(plt_setup_base_smart, df, output_folder, prefix)
pltRHWaitTime(plt_setup_base_smart, df, output_folder, prefix)

