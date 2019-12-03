#!/bin/bash

clear(){
	path=${1}

  # this command allows us not to remove .gitignore files
	cmd="rm -rfv $path/*"
	echo "running $cmd"
	eval "$cmd"
}

remove(){
	path=${1}

	cmd="rm -rfv $path"
	echo "running $cmd"
	eval "$cmd"
}

clear "grafana/log"
remove "grafana/data/png"
remove "grafana/data/grafana.db"

clear "influxdb/data"
clear "influxdb/log"

clear "output"





