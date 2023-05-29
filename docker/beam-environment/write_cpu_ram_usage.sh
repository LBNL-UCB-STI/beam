#!/bin/bash

timeout=20
echo "date,time,CPU usage,RAM used,RAM available"
while sleep $timeout
do
        timestamp_CPU=$(vmstat 1 3 -SM -a -w -t | python3 -c 'import sys; ll=sys.stdin.readlines()[-1].split(); print(ll[-2] + ", " + ll[-1] + ", " + str(100 - int(ll[-5])))')
        ram_used_available=$(free -g | python3 -c 'import sys; ll=sys.stdin.readlines()[-2].split(); print(ll[2] + ", " + ll[-1])')
        echo $timestamp_CPU, $ram_used_available
done
