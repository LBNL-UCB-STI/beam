#!/bin/bash
cd /beam && git checkout "$BRANCH"
echo "$MODE"
cd /opt/calibration
python3 /opt/calibration/correlational.py
python3 /opt/calibration/prepare_new_SSC_run.py

if [[ "$MODE" == 'P' ]]; then
  python3 /opt/calibration/parallelizer.py
else
  python3 /opt/calibration/sequence_script.py
fi