Configure

1. Build docker file with

`docker build . -t autocalibrate`


2. RUN docker file with following environment variables

`conf`: Name of config file name without extention 
`input_dir`: Input directory name from beam i.e. 'test/input/beamville'
`output_dir`: output directory name 'output/beamville/'
`rel_nudge_trials`: Number of steps we need to run for calibration(it should be multiple of 4) 
`beam_iter`: Beam iteration for intercept calibration '0'
`beam_fcf_iter`: Beam iteration for fcf calibration '5'


sudo docker run -d -t -i \ 
-e conf='beam' \
-e input_dir='test/input/beamville' \
-e output_dir='output/beamville/' \
-e rel_nudge_trials='200' \
-e beam_iter='0' \
-e beam_fcf_iter='5' \
-e MODE='S' \
--name autocalibrate autocalibrate