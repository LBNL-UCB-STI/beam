#!/bin/bash
sudo apt install s3fs
echo AKIAIYERCEMQCCNGIWTQ:mjHQEsCymbWx/FiBuL7mkdzl14NdZuZeHodkjB/a > ~/.passwd-s3fs
chmod 600 ~/.passwd-s3fs
mkdir /tmp/cache
chmod 777 /tmp/cache
sudo mkdir ~/s3
sudo s3fs -o use_cache=/tmp/cache -oallow_other beam-outputs ~/s3

# To setup so it can be mounted after reboot
sudo cp .passwd-s3fs /etc/passwd-s3fs
sudo chmod 640 /etc/passwd-s3fs
sudo echo 's3fs#beam-outputs /home/ubuntu/s3 fuse allow_other,use_cache=/tmp/cache 0 0' >> /etc/fstab
