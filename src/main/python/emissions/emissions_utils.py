import pandas as pd
import os
import re


def get_regional_emfac_filename(emfac_data_filepath, emfac_regions, label=""):
    folder_path = os.path.dirname(emfac_data_filepath)
    file_name = os.path.basename(emfac_data_filepath)
    emfac_regions_label = '-'.join([fr"{re.escape(region)}" for region in emfac_regions])
    return folder_path + "/" + emfac_regions_label + "_" + label + file_name


def get_regional_emfac_data(emfac_data_filepath, emfac_regions):
    studyarea_x_filepath = get_regional_emfac_filename(emfac_data_filepath, emfac_regions)

    if os.path.exists(studyarea_x_filepath):
        print("Filtered EMFAC exists. Returning stored output: " + studyarea_x_filepath)
        return pd.read_csv(studyarea_x_filepath)
    else:
        # Load the dataset from the uploaded CSV file
        data = pd.read_csv(emfac_data_filepath)
        # Filter the data for each region in emfac_regions
        pattern = '|'.join([fr"\({re.escape(region)}\)" for region in emfac_regions])
        emfac_filtered = data[data["sub_area"].str.contains(pattern, case=False, na=False)]
        emfac_filtered.to_csv(studyarea_x_filepath, index=False)
        print("Done filtering EMFAC. The output has been stored in: " + studyarea_x_filepath)
        return emfac_filtered
