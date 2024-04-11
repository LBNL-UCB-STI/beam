### A docker files to build a docker image to run `PILATES`

The resulting image won't contain `PILATES` executable files, 
instead, the image will contain required libraries to run `PILATES`. 

The image will also be capable of some additional functionality: 
to run a shell, to run ssh-server and be available for an ssh connection from outside.

### How to build the image

```docker build -t <full image name:version> <path to this folder> ```.


### There are two ways to run `PILATES` with the help of the image:

#### Run in authomatic way

This command will start the image and then the built-in shell script
will execute 'PILATES':

```docker run -d --user=root --privileged -v <path to PILATES folder>:/app/pilates <this image name> run_pilates```

#### Run manually with ssh

This command will start the image and built-in open-ssh server:

```docker run -d --user=root --privileged -v <path to PILATES folder>:/app/pilates -p <open local port>:22 <this image name> ssh```

After the container is run, connect by ssh to the image as `root`. 
It will ask for 'root' password. Currently, the root password is in Docker file
command `echo "root:<password>" | sudo chpasswd`.

```ssh root@localhost -p <open local port from previous command>```

After connecting - move to the mounted folder, pull configured docker images and execute `PILATES` :

```
## move to 'PILATES' folder
cd /app/pilates 

## pull docker images configured in settings.yaml config file
python /misc/pull_images_from_settings_yaml.py settings.yaml

## execute pilates
python run.py
```

#### Run manually directly

```docker run -d --user=root --privileged -v <path to PILATES folder>:/app/pilates -p <open local port>:22 <this image name> bash```

Move to the mounted folder, pull configured docker images and execute `PILATES` :

```
## move to 'PILATES' folder
cd /app/pilates 

## pull docker images configured in settings.yaml config file
python /misc/pull_images_from_settings_yaml.py settings.yaml

## execute pilates
python run.py
```

#### Used docker parameters

`--user=root` is required for built-in micromamba to initialize and to keep root permissions.

`--privileged` is required for built-in docker to work inside container.

`-d` is **optional** and if used will run docker image in a 'detached' mode.
