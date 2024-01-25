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
docker run -v <path to empty folder>:/app/git <full image name:version> git clone https://github.com/LBNL-UCB-STI/beam.git
```

The data might be cloned into `beam/production/<city name>`, or it might be cloned in a different folder.
Here is the command to get the data:

```
docker run -v <path to empty folder>:/app/git <full image name:version> git clone https://github.com/LBNL-UCB-STI/<city name>.git
```

Any git command might be executed in that way: 'git fetch', 'git pull', 'git checkout', e.t.c.

### How to execute BEAM using the image

```
docker run 
    -v <path to BEAM code>:/app/sources 
    -v <path to DATA>:/app/data 
    <full image name:version> 
    gradle run \"-PappArgs=['--config', '<path to beam config>']\" -PmaxRAM=<max RAM for BEAM>
```

Where `path to DATA` should be mounted only if data was cloned into folder other than `beam/production/<city name>`.

`max RAM for BEAM` should an integer number, in Gigabytes.

Any other gradle command might be executed in that way.