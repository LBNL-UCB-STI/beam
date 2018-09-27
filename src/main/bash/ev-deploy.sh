
gradle :execute -PmainClass=beam.experiment.ExperimentGenerator -PappArgs="['--experiments', 'production/application-sfbay/experiments/ev-fleet-qos-S60/ev-fleet.yml']"
cd production/application-sfbay/experiments/ev-fleet-qos-S60/runs
sudo sed -i '0,/\/experiments\/ev-fleet-qos/{s/\/experiments\/ev-fleet-qos//}' */beam.conf

./production/application-sfbay/experiments/ev-fleet-qos-S60/runs/batchRunExperiment.sh &
