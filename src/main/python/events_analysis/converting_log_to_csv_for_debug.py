def read_file_and_filter(specific_sentence, input_file_path, output_file_path):
    # Open the input file and the output file
    with open(input_file_path, 'r') as input_file, open(output_file_path, 'w') as output_file:
        # Iterate over each line in the input file
        for line in input_file:
            # Check if the specific sentence is in the current line
            if specific_sentence in line:
                # Write the line to the output file
                output_file.write(line)

    print("File filtering complete. Check the output file.")


def simple_conversion_into_csv(input_file_path, output_file_path):
    # Open the input file and the output file
    with open(input_file_path, 'r') as input_file, open(output_file_path, 'w') as output_file:
        # Iterate over each line in the input file
        for line in input_file:
            # Replace any number of whitespaces with a comma
            #new_line = re.sub(r'\s+', ',', line.strip())
            new_line = re.sub(r' ', ',', line.strip())
            # Write the modified line to the output file
            output_file.write(new_line + '\n')

    print("Conversion complete. Check the CSV file.")


# ############################
# ########## Main
# ############################

import os
import re

dir_path = "~/Workspace/Data/FREIGHT/sfbay/beam/runs/baseline/2018_routeE_new/"

# read_file_and_filter("RouteE: Vehicle", os.path.expanduser(dir_path + "beamLog.out"), os.path.expanduser(dir_path + "beamLog.filtered.out"))

simple_conversion_into_csv(os.path.expanduser(dir_path + "beamLog.filtered.out"), os.path.expanduser(dir_path + "beamLog.filtered.csv"))