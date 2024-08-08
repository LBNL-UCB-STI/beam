### A docker files to build a docker image to run `BEAM`

The resulting image won't contain `BEAM` executable files, 
instead, the image will contain required libraries to pull, compile and run `BEAM`. 

The image is also capable of some additional functionality: 
to run a shell, to execute git commands, to execute gradle commands, 
to run ssh-server and be available for an ssh connection from outside.

### How to build the image

```
docker build -t <full image name:version> <path to this folder> -f <path to selected Docker file>
```

### How to clone BEAM using the image

The BEAM is a combination of code and data, here is the command to get the code:

```
docker run -it -v <path to empty folder>:/root/git <full image name:version> git clone https://github.com/LBNL-UCB-STI/beam.git
```

The data might be cloned into `beam/production/<city name>`, or it might be cloned in a different folder.
Here is the command to get the data:

```
docker run -it -v <path to empty folder>:/root/git <full image name:version> git clone https://github.com/LBNL-UCB-STI/<city name>.git
```

Any git command might be executed in that way: 'git fetch', 'git pull', 'git checkout', e.t.c.

### How to execute BEAM using the image

```
docker run 
    -it 
    -v <path to BEAM code>:/root/sources 
    -v <path to DATA>:/root/data 
    <full image name:version> 
    gradle run \"-PappArgs=['--config', '<path to beam config>']\" -PmaxRAM=<max RAM for BEAM>
```

Where `path to DATA` should be mounted only if data was cloned into folder other than `beam/production/<city name>`.

`max RAM for BEAM` should an integer number, in Gigabytes.

Any other gradle command might be executed in that way.

### How to connect to a shell inside image

```
docker run -it <any mounting options> <full image name:version> bash
```

### How to start an open-ssh server and connect to it

```
docker run -d <any mounting options> -p <local port>:22 <full image name:version> ssh
```

The password for now is hardcoded into Docker file, in `echo 'root:<password>' | chpasswd` command.

In order to connect to the image: `ssh root@localhost -p <local port>`

### How to use the image in automatic mode

There is a built-in shell script to run BEAM automatically. Currently, it is required for Lawrewncium cluster.

#### Example script #1

This script will start beam-environment docker image,
it will pull the latest BEAM code (branch 'develop') from github,
it won't send notifications and won't publish output to s3,
the selected config - beamville, will be used from the pulled code.
The output folder will be created before running the docker image.


```
#!/bin/bash

OUT_PATH=$(realpath "<path to where beam will be cloned>")
mkdir -m 777 "$OUT_PATH"
echo "Using folder for beam at '$OUT_PATH'"

MAX_RAM="16"
BEAM_CONFIG="test/input/beamville/beam.conf"
BEAM_BRANCH_NAME="develop"

docker run \
  --network host \
  --env MAX_RAM=$MAX_RAM \
  --env BEAM_CONFIG="$BEAM_CONFIG" \
  --env BEAM_BRANCH_NAME=$BEAM_BRANCH_NAME \
  --env PULL_CODE=true \
  --mount source="$OUT_PATH",destination=/root/sources,type=bind \
  <full image name:version>
```

#### Example script #2


This script will start beam-environment docker image,
it will use the code from mounted folder, the mounted folder should point to BEAM root,
it won't send notifications and won't publish output to s3,
the selected config - beamville, will be used from the pulled code.
The output folder will be created before running the docker image.

```
#!/bin/bash

MAX_RAM="16"
BEAM_CONFIG="test/input/beamville/beam.conf"
EXISTING_CODE_PATH="<path to existing beam folder>"

docker run \
  --network host \
  --env MAX_RAM=$MAX_RAM \
  --env BEAM_CONFIG=$BEAM_CONFIG \
  --mount source="$EXISTING_CODE_PATH",destination=/root/sources,type=bind \
  <full image name:version>
```

#### Example script #3

This script will start beam-environment docker image,
it will use the code from mounted folder, the mounted folder should point to BEAM root,
it will use the data from mounted data folder if selected config is in there,
otherwise it will try to use data from code folder (i.e. from test/input folder)
it won't send notifications and won't publish output to s3,
the selected config - beamville, will be used from the pulled code.
The output folder will be created before running the docker image.

```
#!/bin/bash

MAX_RAM="16"
BEAM_CONFIG="test/input/beamville/beam.conf"
EXISTING_CODE_PATH="<path to existing beam code>"
EXISTING_DATA_PATH="<path to existing beam DATA>"

docker run \
  --network host \
  --env MAX_RAM=$MAX_RAM \
  --env BEAM_CONFIG=$BEAM_CONFIG \
  --mount source="$EXISTING_CODE_PATH",destination=/root/sources,type=bind \
  --mount source="$EXISTING_DATA_PATH",destination=/root/data,type=bind \
  <full image name:version>
```


