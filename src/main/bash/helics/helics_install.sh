#!/bin/bash

pip install setuptools
pip install strip-hints
pip install helics==3.3.0
pip install helics-apps==3.3.0

python3 -c "import helics; print('the version of installed helics: ' + helics.helicsGetVersion())"
