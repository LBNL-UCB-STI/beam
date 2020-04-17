#!/bin/bash

# Taken from https://github.com/grafana/grafana-docker/issues/74

# Script to configure grafana datasources and dashboards.
# Intended to be run before grafana entrypoint...
# Image: grafana/grafana:4.1.2
# ENTRYPOINT [\"/run.sh\"]"

GRAFANA_URL=${GRAFANA_URL:-http://$GF_SECURITY_ADMIN_USER:$GF_SECURITY_ADMIN_PASSWORD@localhost:3003}
#GRAFANA_URL=http://grafana-plain.k8s.playground1.aws.ad.zopa.com
DATASOURCES_PATH=${DATASOURCES_PATH:-/etc/grafana/datasources}
DASHBOARDS_PATH=${DASHBOARDS_PATH:-/etc/grafana/dashboards}

# Generic function to call the Vault API
grafana_api() {
  local verb=$1
  local url=$2
  local params=$3
  local bodyfile=$4
  local cmd

  cmd="curl -L -s --fail -H \"Accept: application/json\" -H \"Content-Type: application/json\" -X ${verb} -k ${GRAFANA_URL}${url}"
  [[ -n "${params}" ]] && cmd="${cmd} -d \"${params}\""
  [[ -n "${bodyfile}" ]] && cmd="${cmd} --data @${bodyfile}"
  echo "Running ${cmd}"
  eval ${cmd} || return 1
  return 0
}

wait_for_api() {
  while ! grafana_api GET /api/user/preferences
  do
    sleep 5
  done 
}

install_datasources() {
  local datasource

  for datasource in ${DATASOURCES_PATH}/*.json
  do
    if [[ -f "${datasource}" ]]; then
      echo "Installing datasource ${datasource}"
      if grafana_api POST /api/datasources "" "${datasource}"; then
        echo " installed ok"
      else
        echo "install failed"
      fi
    fi
  done
}

install_dashboards() {
  local dashboard

  for dashboard in ${DASHBOARDS_PATH}/*.json
  do
    if [[ -f "${dashboard}" ]]; then
      echo "Installing dashboard ${dashboard}"

      if grafana_api POST /api/dashboards/db "" "${dashboard}"; then
        echo " installed ok"
      else
        echo "install failed"
      fi

    fi
  done
}

configure_grafana() {
  wait_for_api
  install_datasources
  install_dashboards

  echo "Grafana is ready"
}

wait_for_influx_db() {
  influx -execute "SHOW DATABASES"
  while [ $? -ne 0 ]; do
    influx -execute "SHOW DATABASES"
  done
}

create_or_restore_beam_database() {
  target=/var/lib/influxdb/snapshot

  if find "$target" -mindepth 1 -print -quit 2>/dev/null | grep -q .; then
    # not empty, probably there is a snapshot
    influxd restore -portable $target
    echo "beam database restored"
  else
    # is empty, nothing to restore
    influx -execute "CREATE DATABASE beam"
    echo "beam database created"
  fi
}

configure_influx_db() {
  wait_for_influx_db
  create_or_restore_beam_database

  echo "InfluxDB is ready"
}

echo "Running configure_grafana in the background..."
configure_grafana &

echo "Running configure_influx_db in the background..."
configure_influx_db &

# this script will be used to allow everyone to delete collected by grafana and indluxDB data and logs
echo "chmod 777 -R /var/lib/influxdb/snapshot" >> chmodfolders.sh
chmod +x chmodfolders.sh

echo "service grafana-server stop" >> stopservices.sh
echo "service influxdb stop" >> stopservices.sh
chmod +x stopservices.sh

echo "rm -f /var/lib/influxdb/snapshot/*" >> backup.sh
echo "influxd backup -portable /var/lib/influxdb/snapshot" >> backup.sh
chmod +x backup.sh

/run.sh

exit 0
