rmdir /s /q grafana\log
mkdir grafana\log
rmdir /s /q grafana\data\png
del /F /Q /S grafana\data\grafana.db

rmdir /s /q influxdb\data
mkdir influxdb\data
rmdir /s /q influxdb\log
mkdir influxdb\log

rmdir /s /q output
mkdir output
