import openmatrix as omx
import os
import pandas as pd

work_dir = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/validation")

# Define the path to your OMX file
omx_file_path = work_dir + "/0.activitySimODSkims_current.omx"


# Open the OMX file for reading
with omx.open_file(omx_file_path, "r") as omx_file:
    # Convert OMX file to a pandas dataframe
    df = pd.DataFrame(omx_file["DRV_COM_WLK_BOARDS__AM"][:])

    # Write the dataframe to a CSV file
    df.to_csv(work_dir + "/0.activitySimODSkims_current.csv", index=False)

    # Get the number of data sets in the file
    #print('Shape:', omx_file.shape())  # (100,100)
    #print('Number of tables:', len(omx_file))  # 3
    #print('Table names:', omx_file.list_matrices())  # ['m1','m2',',m3']

# Close the OMX file
omx_file.close()