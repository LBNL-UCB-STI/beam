# Generating Count Files from PeMS Data

The general workflow is this:

## `PeMS_Tools` Steps
1) Download everything w/ [PeMS Tools](https://github.com/sfwatergit/PeMS_Tools) (or use Nine Counties SF Bay Area 2015 outputs of this tool, see link below).

2) Process them to create the "time series" directories. This is basically a text-file based database that creates a continuous time series of data, reported in 5-minute intervals, for each station over the period of downloaded data.

## `counts_tools` Steps
The naming schema is pretty straight forward. Config files go with the matching executable in the exec folder.

1) Match sensors to network
2) Filter sensors
3) Generate MATSim counts files 



filter_stations -- runs a sequence of heuristics to filter out bad stations. This is what reduces the population sensors from 4k to 700ish

create_PeMS_Tools_counts_multidays -- this is what builds the actual MATSim counts file. You can do things like get the "typical weekday" (Tu, Wed, Thr) over a date range, or produce counts for a single day.

Preprocessed Data Location

The results of these steps  afor 2015 are in our old Drive:

https://drive.google.com/drive/u/1/folders/0B5DYEo0XCyF6V0trdDMxd0hnMWs

The 2015_all folder is the "ts_dir" directory referred to in some of the config files in the steps below.

The cleaned_results.csv is a list of all the stations that passed the filters in step 4. I highly recommend using this subset for your metrics.
