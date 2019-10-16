import pandas as pd
import smartplots3_setup

def createSetup(name,expansion_factor,plot_size,settings):
    plt_setup_smart={
        'name': name,
        'expansion_factor':expansion_factor,
        'scenarios_itr': [],
        'scenarios_id':[],
        'scenarios_year':[],
        'plot_size': plot_size,
        'bottom_labels': [],
        'top_labels': [],
        'plots_folder': "makeplots3_2"
    }
    plt_setup_smart['name']=name
    plt_setup_smart['expansion_factor']=expansion_factor
    plt_setup_smart['plot_size']=plot_size

    plt_setup_smart['scenarios_year']=[]
    plt_setup_smart['scenarios_id']=[]
    plt_setup_smart['scenarios_itr']=[]
    plt_setup_smart['top_labels']=[]

    for (scenarios_year,scenarios_id,scenarios_itr,bottom_label,top_label) in settings:
        plt_setup_smart['scenarios_year'].append(scenarios_year)
        plt_setup_smart['scenarios_id'].append(scenarios_id)
        plt_setup_smart['scenarios_itr'].append(scenarios_itr)
        plt_setup_smart['top_labels'].append(top_label)
        plt_setup_smart['bottom_labels'].append(bottom_label)

    return plt_setup_smart

def createSettingRow(scenarios_year,scenarios_id,scenarios_itr,bottom_label,top_label):
    return (scenarios_year,scenarios_id,scenarios_itr,bottom_label,top_label)

settings=[]
settings.append(createSettingRow(2010,1,15,"Base_CL_CT", ""))
settings.append(createSettingRow(2025,6,15,"A_STL_STT_BAU", ""))
settings.append(createSettingRow(2025,7,15,"A_STL_STT_VTO", ""))
settings.append(createSettingRow(2040,8,15,"B_LTL_LTT_BAU", ""))
settings.append(createSettingRow(2040,9,15,"B_LTL_LTT_VTO", ""))
settings.append(createSettingRow(2040,10,15,"C_LTL_LTT_BAU", ""))
settings.append(createSettingRow(2040,11,15,"C_LTL_LTT_VTO", ""))
plt_setup_smart3 = createSetup('7scenarios', (7.75/0.315) * 27.0 / 21.3,(8, 4.5),settings)

output_folder = "/Users/haitam/workspace/pyscripts/data/smart/20thSep2019"

smartplots3_setup.pltAveragePersonSpeed_allModes(plt_setup_smart3, output_folder)
smartplots3_setup.pltAveragePersonSpeed_car(plt_setup_smart3, output_folder)
smartplots3_setup.pltAverageLDVSpeed(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitByTrips(plt_setup_smart3, output_folder)
smartplots3_setup.tableSummary(plt_setup_smart3, output_folder)

smartplots3_setup.pltLdvRhOccupancy(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitInPMT(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitInPMTPerCapita(plt_setup_smart3, output_folder)
smartplots3_setup.pltLdvTechnologySplitInVMT(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitInVMT(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitInVMT_withoutNonMotorizedMode(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitInVMTPerCapita(plt_setup_smart3, output_folder)
smartplots3_setup.pltModeSplitInVMTPerCapita_withoutNonMotorizedMode(plt_setup_smart3, output_folder)
smartplots3_setup.pltRHEmptyPooled(plt_setup_smart3, output_folder)
smartplots3_setup.pltLdvRhOccupancyByVMT(plt_setup_smart3, output_folder)
smartplots3_setup.pltOverallAverageSpeed(plt_setup_smart3, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3, output_folder)


settings=[]
settings.append(createSettingRow(2010,1,15,"Base_CL_CT", ""))
settings.append(createSettingRow(2025,2,15,"Base_STL_STT_BAU", ""))
settings.append(createSettingRow(2025,3,15,"Base_STL_STT_VTO", ""))
settings.append(createSettingRow(2040,4,15,"Base_LTL_LTT_BAU", ""))
settings.append(createSettingRow(2040,5,15,"Base_LTL_LTT_VTO", ""))
settings.append(createSettingRow(2025,6,15,"A_STL_STT_BAU", ""))
settings.append(createSettingRow(2025,7,15,"A_STL_STT_VTO", ""))
settings.append(createSettingRow(2040,8,15,"B_LTL_LTT_BAU", ""))
settings.append(createSettingRow(2040,9,15,"B_LTL_LTT_VTO", ""))
settings.append(createSettingRow(2040,10,15,"C_LTL_LTT_BAU", ""))
settings.append(createSettingRow(2040,11,15,"C_LTL_LTT_VTO", ""))
plt_setup_smart3_base = createSetup('11scenarios', (7.75/0.315) * 27.0 / 21.3,(10, 4.5), settings)

smartplots3_setup.pltEnergyPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvPersonHourTraveled(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3_base, output_folder)
smartplots3_setup.tableSummary(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAverageLDVSpeed(plt_setup_smart3_base, output_folder)


settings=[]
settings.append(createSettingRow(2010,1,15,"Base_CL_CT", ""))
settings.append(createSettingRow(2025,1,15,"Base_STL_STT_BAU", ""))
settings.append(createSettingRow(2040,1,15,"Base_LTL_LTT_BAU", ""))

plt_setup_smart3_base = createSetup('3base', (7.75/0.315) * 27.0 / 21.3, (3, 3.5), settings)

smartplots3_setup.pltAveragePersonSpeed_allModes(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAveragePersonSpeed_car(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAverageLDVSpeed(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitByTrips(plt_setup_smart3_base, output_folder)
smartplots3_setup.tableSummary(plt_setup_smart3_base, output_folder)

smartplots3_setup.pltLdvRhOccupancy(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInPMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInPMTPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvTechnologySplitInVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMTPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMTPerCapita_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHEmptyPooled(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvRhOccupancyByVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltOverallAverageSpeed(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltEnergyPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvPersonHourTraveled(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3_base, output_folder)
smartplots3_setup.tableSummary(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAverageLDVSpeed(plt_setup_smart3_base, output_folder)

settings=[]
settings.append(createSettingRow(2010,1,15,"Base_CL_CT", ""))
settings.append(createSettingRow(2010,6,15,"Base_STL_STT_BAU", ""))
settings.append(createSettingRow(2010,7,15,"Base_STL_STT_VTO", ""))
settings.append(createSettingRow(2010,8,15,"Base_LTL_LTT_BAU", ""))
settings.append(createSettingRow(2010,9,15,"Base_LTL_LTT_VTO", ""))
settings.append(createSettingRow(2010,10,15,"A_STL_STT_BAU", ""))
settings.append(createSettingRow(2010,11,15,"A_STL_STT_VTO", ""))

plt_setup_smart3_base = createSetup('all2010', (7.75/0.315) * 27.0 / 21.3, (8, 4.5), settings)

smartplots3_setup.pltAveragePersonSpeed_allModes(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAveragePersonSpeed_car(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAverageLDVSpeed(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitByTrips(plt_setup_smart3_base, output_folder)
smartplots3_setup.tableSummary(plt_setup_smart3_base, output_folder)

smartplots3_setup.pltLdvRhOccupancy(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInPMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInPMTPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvTechnologySplitInVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMTPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMTPerCapita_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHEmptyPooled(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvRhOccupancyByVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltOverallAverageSpeed(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltEnergyPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvPersonHourTraveled(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3_base, output_folder)
smartplots3_setup.tableSummary(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT_withoutNonMotorizedMode(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAverageLDVSpeed(plt_setup_smart3_base, output_folder)