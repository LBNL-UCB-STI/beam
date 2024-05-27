import sys
import os
import json
from analyze_events import get_pooling_metrics
from analyze_events import get_pooling_sankey_diagram

# When beam executes an iteration python script it provides the following command line arguments
# 1. path to the beam config
# 2. path to the iteration output dir
# 3. the iteration number
if len(sys.argv) <= 1:
    print("This script is supposed to be run by Beam")
    exit(1)
iteration_path = sys.argv[2]
iteration_number = sys.argv[3]
possibleEventFiles = [f"{iteration_path}/{iteration_number}.events.csv",
                      f"{iteration_path}/{iteration_number}.events.csv.gz"]

event_file = next((x for x in possibleEventFiles if os.path.exists(x)), None)

if not event_file:
    print(f"Cannot find event files within " + str(possibleEventFiles))
else:
    print("getting pooling metrics from events file: " + event_file)
    pooling_metrics = get_pooling_metrics(event_file)
    name = event_file.rsplit('/', 1)[0]
    with open('{}/pooling-metrics.json'.format(name), 'w') as f:
        json.dump(pooling_metrics, f)
    unit = 1000.0
    get_pooling_sankey_diagram(pooling_metrics, name, unit)
    print(json.dumps(pooling_metrics, indent=4))
    print("done")
