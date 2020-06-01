#!/usr/bin/env python3
"""Generate a text files which is later used by google route reader to get the routes
Example of usage:
python extract_google_links.py \
--inputFolder="/path/to/folder/"

"""

import getopt
import glob
import random
import re
import sys
import os
from pathlib import Path
from urllib.parse import urlparse

import pandas as pd
import datetime

def main(args):
    program_arguments = parse_parameters(args)
    input_folder_location = search_argument("--inputFolder", program_arguments)
    for file in os.listdir(input_folder_location):
        if file.endswith(".csv"):
            extract_google_links(input_folder_location + "/" + file)

def extract_google_links(file):
    df = pd.read_csv(file)
    link_df = df['google_link']
    original_file_no_ext = Path(file).stem
    original_file = Path(file).name
    folder = Path(file).parent
    link_file = folder.joinpath(original_file_no_ext + "_links.txt").resolve()
    link_df.to_csv(link_file, header = False, index = False)
    print("Extracted links from file %s to %s" % (original_file, link_file))

def search_argument(argument_name, arguments):
    the_tuple = filter(lambda x: x[0] == argument_name, arguments)
    result = next(the_tuple, None)
    if result is None:
        return None
    else:
        return result[1]

def parse_parameters(args):
    short_options = ""
    long_options = ["help",
                    "inputFolder="]
    try:
        arguments, _ = getopt.getopt(args[1:], short_options, long_options)
        if search_argument("--inputFolder", arguments) is None:
            raise RuntimeError('inputFolder is not provided')
        return arguments
    except getopt.error as err:
        print(str(err))
        sys.exit(2)

if __name__ == "__main__":
    main(sys.argv)
