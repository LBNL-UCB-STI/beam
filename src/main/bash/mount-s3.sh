#!/usr/bin/env bash
sudo apt install s3fs
echo $1 > ~/.passwd-s3fs
chmod 600 ~/.passwd-s3fs
mkdir ~/.cache-s3
chmod 777 ~/.cache-s3
mkdir ~/s3
s3fs -o use_cache=/.cache-s3 -oallow_other beam-outputs ~/s3

# To setup so it can be mounted after reboot
sudo cp .passwd-s3fs /etc/passwd-s3fs
sudo chmod 640 /etc/passwd-s3fs
sudo echo 'beam-outputs /home/ubuntu/s3 fuse.s3fs _netdev,allow_other,use_cache=/home/ubuntu/.cache-s3 0 0' >> /etc/fstab
