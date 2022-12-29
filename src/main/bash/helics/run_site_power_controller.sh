#!/bin/bash

echo "installing helics ..."
chmod +x helics_install.sh
./helics_install.sh

cd /home/ubuntu/git/beam/src/main/python
sudo chown ubuntu:ubuntu -R gemini
cd -

cd /home/ubuntu/git/beam/src/main/python/gemini/cosimulation

now="$(date +"%Y_%m_%d_%I_%M_%p")"
python3 beam_pydss_broker.py 3 > "output_${now}_broker.log" &
echo "broker started"
sleep 5s

python3 site_power_controller_federate.py 1 > "output_${now}_site_power_controller.log" &
echo "site power controller started"
sleep 5s

helics_recorder beam_recorder.txt --output=recording_output.txt > "output_${now}_recorder.log" &
echo "recorder started"
sleep 5s

cd -