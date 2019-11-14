clearDir(){
	path=${1}

	cmd="rm -rfv $path/*"
	echo "running $cmd"
	eval "$cmd"
}

clearDir "grafana/data"
clearDir "grafana/log"

clearDir "influxdb/data"
clearDir "influxdb/log"

