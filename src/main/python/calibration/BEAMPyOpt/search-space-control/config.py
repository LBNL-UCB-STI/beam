import os
#beam = f'{os.getcwd()}'
beam = '/home/ubuntu/git/beam'
beam_config = 'urbansim-10k'
total_rel_nudge_trials = 200 # should always be even and multiple of 4

shared = f'{beam}/src/main/python/calibration/BEAMPyOpt/storage'
search_space = f'{beam}/src/main/python/calibration/BEAMPyOpt/search-space-control'

sf_light_ip_dir = f'{beam}/test/input/sf-light'
sf_light_dir = f'{beam}/output/sf-light/*'

base_urbansim_config = f'{sf_light_ip_dir}/{beam_config}.conf'
copy_urbansim_config = f'{sf_light_ip_dir}/{beam_config}_%d.conf'
copy_urbansim_txt = f'{sf_light_ip_dir}/{beam_config}_%d.txt'


output_csv = f'{shared}/%d_%d.csv'

writecue = f'{beam}/writecue.txt'
firecue = f'{beam}/firecue.txt'

init_runs = 16

parallel_run = 16