#!/bin/bash

echo "installing helics and dependencies ..."
apt install python3-pip -y

pip3 install setuptools
pip3 install strip-hints
pip3 install helics==3.3.0
pip3 install helics-apps==3.3.0

python3 -c "import helics; print('the version of installed helics: ' + helics.helicsGetVersion())"

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
