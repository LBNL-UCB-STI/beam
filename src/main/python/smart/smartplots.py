from smartplots_setup import pltModeSplitByTrips
from smartplots_setup import pltEnergyPerCapita
from smartplots_setup import pltLdvRhOccupancy
from smartplots_setup import pltLdvPersonHourTraveled
from smartplots_setup import pltModeSplitInPMT
from smartplots_setup import pltModeSplitInVMT
from smartplots_setup import pltLdvTechnologySplitInVMT
from smartplots_setup import pltRHWaitTime
from smartplots_setup import pltRHEmptyPooled
from smartplots_setup import tableSummary


from smartplots_setup import plt_setup_smart
from smartplots_setup import plt_setup_base_smart
import pandas as pd


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

pltEnergyPerCapita(plt_setup_base_smart, df, output_folder, prefix)
pltLdvPersonHourTraveled(plt_setup_base_smart, df, output_folder, prefix)
pltRHWaitTime(plt_setup_base_smart, df, output_folder, prefix)

