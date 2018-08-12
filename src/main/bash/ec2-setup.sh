#!/usr/bin/env bash

# Install oracle java 8
echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo debconf-set-selections && \
sudo add-apt-repository -y ppa:webupd8team/java && \
sudo apt-get update && \
sudo apt-get install -y oracle-java8-installer && \
sudo rm -rf /var/lib/apt/lists/* && \
sudo rm -rf /var/cache/oracle-jdk8-installer

# Install dependencies (zip, unzip, awscli, git-lfs)
curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.deb.sh | sudo bash
sudo apt-get update
sudo apt-get install zip unzip awscli git-lfs -y
git lfs install

# Install gradle 3.5
wget -O gradle.zip "https://services.gradle.org/distributions/gradle-3.5-bin.zip"
sudo mkdir /opt/gradle
sudo unzip -d /opt/gradle gradle-3.5-bin.zip

export PATH=$PATH:/opt/gradle/gradle-3.5/bin


