@echo off
if [%1]==[] goto usage

set config=%1
set "config=%config:\=/%"

set input=%cd%\input
set output=%cd%\output

call docker run --mount source=%input%,destination=/input,type=bind --mount source=%output%,destination=/output,type=bind --link docker-influxdb-grafana:metrics --net beam_default --env JAVA_OPTS="-Xmx8g -Xms4g -Dlogback.configurationFile=logback.xml" beammodel/beam:workshop --config=%config%
goto :eof

:usage
echo path to config file from "input" folder is required
echo example: run-beam.cmd input/beamville/beam.conf
pause