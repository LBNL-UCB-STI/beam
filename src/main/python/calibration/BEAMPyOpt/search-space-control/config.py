import os
beam = f'{os.getcwd()}'

total_rel_nudge_trials = 300 # should always be even and multiple of 4

shared = f'{beam}/src/main/python/calibration/BEAMPyOpt/storage'
search_space = f'{beam}/src/main/python/calibration/BEAMPyOpt/search-space-control'

base_urbansim_config = f'{beam}/test/input/sf-light/urbansim-10k.conf'
copy_urbansim_config = f'{beam}/test/input/sf-light/urbansim-10k_%d.conf'
copy_urbansim_txt = f'{beam}/test/input/sf-light/urbansim-10k_%d.txt'

sf_light_ip_dir = f'{beam}/test/input/sf-light'

sf_light_dir = f'{beam}/output/sf-light/*'

output_csv = f'{shared}/%d_%d.csv'

writecue = f'{beam}/writecue.txt'
firecue = f'{beam}/firecue.txt'

init_runs = 16