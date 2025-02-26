from validation_utils import download_h5_data
import os

# url = "https://console.cloud.google.com/storage/browser/_details/beam-core-outputs/urbansim-inputs/custom_mpo_06197001_model_data_2017.h5;tab=live_object?project=beam-core"
url = "https://storage.googleapis.com/beam-core-outputs/urbansim-inputs/custom_mpo_06197001_model_data_2017.h5"
h5_path = os.path.expanduser("~/Workspace/Simulation/sfbay/urbansim/custom_mpo_06197001_model_data.h5")

download_h5_data(url, h5_path)
