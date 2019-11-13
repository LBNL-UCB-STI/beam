import pandas as pd
import smartplots3_setup

def createSetup(name,expansion_factor,percapita_factor,plot_size,settings):
    plt_setup_smart={
        'name': name,
        'expansion_factor':expansion_factor,
        'percapita_factor':percapita_factor,
        'scenarios_itr': [],
        'scenarios_id':[],
        'scenarios_year':[],
        'plot_size': plot_size,
        'bottom_labels': [],
        'top_labels': [],
        'plots_folder': "makeplots3"
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

scenarios_lables = {
    "Base_CL_CT": "Base0",
    "Base_STL_STT_BAU": "Base2",
    "Base_STL_STT_VTO": "Base3",
    "Base_LTL_LTT_BAU": "Base5",
    "Base_LTL_LTT_VTO": "Base6",
    "A_STL_STT_BAU": "A2",
    "A_STL_STT_VTO": "A3",
    "B_LTL_LTT_BAU": "B5",
    "B_LTL_LTT_VTO": "B6",
    "C_LTL_LTT_BAU": "C5",
    "C_LTL_LTT_VTO": "C6"
}
output_folder = "/home/ubuntu/git/jupyter/data/28thOct2019"

# Base_CL_CT
# A_STL_STT_BAU
settings=[]
settings.append(createSettingRow(2010,1,15,scenarios_lables["Base_CL_CT"], ""))
settings.append(createSettingRow(2025,6,15,scenarios_lables["A_STL_STT_BAU"], ""))
settings.append(createSettingRow(2025,7,15,scenarios_lables["A_STL_STT_VTO"], ""))
settings.append(createSettingRow(2040,8,15,scenarios_lables["B_LTL_LTT_BAU"], ""))
settings.append(createSettingRow(2040,9,15,scenarios_lables["B_LTL_LTT_VTO"], ""))
settings.append(createSettingRow(2040,10,15,scenarios_lables["C_LTL_LTT_BAU"], ""))
settings.append(createSettingRow(2040,11,15,scenarios_lables["C_LTL_LTT_VTO"], ""))
plt_setup_smart3 = createSetup('7scenarios', (7.75/0.315) * 27.0 / 21.3, 27.0/21.3, (8, 4.5),  settings)

#smartplots3_setup.pltRealizedModeSplitByTrips(plt_setup_smart3, output_folder)
#smartplots3_setup.pltModeSplitInPMTPerCapita(plt_setup_smart3, output_folder)
#smartplots3_setup.pltAveragePersonSpeed_allModes(plt_setup_smart3, output_folder)
#smartplots3_setup.pltAveragePersonSpeed_car(plt_setup_smart3, output_folder)
#smartplots3_setup.pltModeSplitInVMT(plt_setup_smart3, output_folder)
#smartplots3_setup.pltRHEmptyPooled(plt_setup_smart3, output_folder)
#smartplots3_setup.pltRHWaitTime(plt_setup_smart3, output_folder)
#smartplots3_setup.pltLdvTechnologySplitInVMT(plt_setup_smart3, output_folder)

settings=[]
settings.append(createSettingRow(2010,1,15,scenarios_lables["Base_CL_CT"], ""))
settings.append(createSettingRow(2025,2,15,scenarios_lables["Base_STL_STT_BAU"], ""))
settings.append(createSettingRow(2025,3,15,scenarios_lables["Base_STL_STT_VTO"], ""))
settings.append(createSettingRow(2040,4,15,scenarios_lables["Base_LTL_LTT_BAU"], ""))
settings.append(createSettingRow(2040,5,15,scenarios_lables["Base_LTL_LTT_VTO"], ""))
settings.append(createSettingRow(2025,6,15,scenarios_lables["A_STL_STT_BAU"], ""))
settings.append(createSettingRow(2025,7,15,scenarios_lables["A_STL_STT_VTO"], ""))
settings.append(createSettingRow(2040,8,15,scenarios_lables["B_LTL_LTT_BAU"], ""))
settings.append(createSettingRow(2040,9,15,scenarios_lables["B_LTL_LTT_VTO"], ""))
settings.append(createSettingRow(2040,10,15,scenarios_lables["C_LTL_LTT_BAU"], ""))
settings.append(createSettingRow(2040,11,15,scenarios_lables["C_LTL_LTT_VTO"], ""))
plt_setup_smart3_base = createSetup('11scenarios', (7.75/0.315) * 27.0 / 21.3, 27.0/21.3, (10, 4.5), settings)

smartplots3_setup.pltEnergyPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRealizedModeSplitByTrips(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInPMTPerCapita(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAveragePersonSpeed_allModes(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltAveragePersonSpeed_car(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltModeSplitInVMT(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHEmptyPooled(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltRHWaitTime(plt_setup_smart3_base, output_folder)
smartplots3_setup.pltLdvTechnologySplitInVMT(plt_setup_smart3_base, output_folder)

#smartplots3_setup.pltMEP(plt_setup_smart3, output_folder, [15071,21151,22872,29014,27541,36325,45267])
smartplots3_setup.tableSummary(plt_setup_smart3_base, output_folder)