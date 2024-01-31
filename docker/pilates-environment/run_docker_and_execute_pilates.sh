echo "Running docker service"
sudo service docker start

number_of_seconds_left=20  # Set the max number of loop iterations
wait_timeout=2

while (! docker stats --no-stream &> /dev/null); do
  # Docker takes a few seconds to initialize
  echo "Waiting for Docker to launch [$number_of_seconds_left seconds left] ..."
  sleep $wait_timeout
  number_of_seconds_left=$((number_of_seconds_left - wait_timeout))
  if [ $number_of_seconds_left -eq 0 ]; then
    echo "Docker failed to launch (one of solutions might be to run this image with '--privileged' flag)"
    exit 1
  fi
done

print_pilates_missing(){
  echo "The directory '/root/pilates' does not exist. "
  echo "The directory should be mounted  '-v <local-pilates-path>:/root/pilates' "
  echo "and should contain 'run.py' and 'settings.yaml' files."

  exit 1
}

pilates_dir="/root/pilates"
cd "$pilates_dir" || print_pilates_missing

echo "Pulling images described in /root/pilates/settings.yaml"
python3 /misc/pull_images_from_settings_yaml.py settings.yaml

echo "Running pilates: 'python3 run.py'"
python3 run.py 2>&1 | tee "pilates_execution_$(date +'%Y%m%d_%H%M%S').log"
