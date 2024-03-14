import numpy as np
import openmatrix as omx

# Assuming 'zone_id_map' is your dictionary mapping old zone IDs to new zone IDs
zone_id_map = {1: 100, 2: 200, 3: 300, 4: 400}  # Example mapping

# Load the existing OMX file
with omx.open_file('path_to_your_existing_file.omx', 'r') as omx_file:
    # Read zone IDs from the existing file
    old_zone_ids = omx_file.mapping('zone_id')

    # Apply the mapping to get the new zone IDs
    new_zone_ids = np.array([zone_id_map[zone] for zone in old_zone_ids])

    # Initialize a new OMX file to store the transformed data
    with omx.open_file('path_to_your_new_file.omx', 'w') as new_omx_file:
        # Copy matrices from the old file to the new one, without changes
        for matrix_name in omx_file.list_matrices():
            matrix_data = omx_file[matrix_name][:]
            new_omx_file[matrix_name] = matrix_data

            # Optionally, copy matrix attributes if there are any
            for attr in omx_file[matrix_name].attrs:
                new_omx_file[matrix_name].attrs[attr] = omx_file[matrix_name].attrs[attr]

        # Write the new zone IDs to the new OMX file
        new_omx_file.create_mapping('zone_id', new_zone_ids)

        # Copy file-level attributes
        for attr_name in omx_file.listAllAttributes():
            new_omx_file.attrs[attr_name] = omx_file.attrs[attr_name]