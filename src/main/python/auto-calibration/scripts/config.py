import os
beam = '/beam'
conf_base = os.getenv('conf', 'beam')
scenerio_config_loc = os.getenv('input_dir', 'test/input/beamville')
output_base_dir = os.getenv('output_dir', 'output/beamville/')
sf_light_dir = f'{beam}/{output_base_dir}*'

total_rel_nudge_trials = int(os.getenv('rel_nudge_trials', 200))  #should always be even and multiple of 4
finaliteration = os.getenv('beam_iter', '0')
finaliteration_flowcapacity = os.getenv('beam_fcf_iter', '5')

# not need to pass

sf_light_ip_dir = f'{beam}/{scenerio_config_loc}'
shared = f'{beam}/calibration/storage'
search_space = f'{beam}/calibration'
plot_path = f'{search_space}/plot'


base_urbansim_config = f'{sf_light_ip_dir}/{conf_base}.conf'
copy_urbansim_config = f'{sf_light_ip_dir}/{conf_base}_%d.conf'
copy_urbansim_txt = f'{sf_light_ip_dir}/{conf_base}_%d.txt'

flow_capacity_config = f'{sf_light_ip_dir}/{conf_base}_f_%d_%d.conf'
base_config = f'{sf_light_ip_dir}/{conf_base}_base.conf'

output_csv = f'{shared}/%d_%d.csv'

writecue = f'{beam}/writecue.txt'
firecue = f'{beam}/firecue.txt'

init_runs = 8
parallel_run = 4
fcf_process_seq = [8, 4, 2, 2]
