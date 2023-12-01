FROM ubuntu:latest
RUN apt-get update
# Install OpenJDK-8 & Fix certificate issues
RUN apt-get install -y openjdk-8-jdk ant ca-certificates-java && update-ca-certificates -f;
# Setup JAVA_HOME -- useful for docker commandline
ENV JAVA_HOME="/usr/lib/jvm/java-8-openjdk-amd64/"
# Making sure java 8 is used
RUN export JAVA_HOME
RUN update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-8-openjdk-amd64/bin/java 3
RUN update-alternatives --install /usr/bin/javac javac /usr/lib/jvm/java-8-openjdk-amd64/bin/javac 3
RUN update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/bin/java
RUN update-alternatives --set javac /usr/lib/jvm/java-8-openjdk-amd64/bin/javac
# ssh server and other essentials
RUN apt-get install -y wget curl git unzip build-essential openssh-server python3 python3-pip
# installing correct version of git lfs
RUN wget -O git-lfs.deb https://packagecloud.io/github/git-lfs/packages/debian/stretch/git-lfs_2.3.4_amd64.deb/download && \
    dpkg -i git-lfs.deb && \
    rm git-lfs.deb
# AWS CLI
RUN wget -O awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip &&  \
    unzip awscliv2.zip &&  \
    ./aws/install &&  \
    rm awscliv2.zip && \
    rm -rf aws
# python dependency
RUN pip install pandas plotly kaleido psutil
# working in /app
WORKDIR /app
# configure ssh server
RUN echo "JAVA_HOME=$JAVA_HOME" >> /etc/environment
RUN mkdir /var/run/sshd && \
    echo 'root:beam' | chpasswd && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config && \
    sed -i 's/#PermitUserEnvironment no/PermitUserEnvironment yes/' /etc/ssh/sshd_config
# Expose the SSH port
EXPOSE 22
# prepare folders where the image expect data
RUN bash -c 'mkdir -p /app/{git,data,common,sources,gradle_cache}'
# mark folders in order to determine if they were mounted
RUN touch /app/git/.this_volume_was_not_mounted.txt
RUN touch /app/gradle_cache/.this_volume_was_not_mounted.txt
# add files
ADD replace_config_path_in_args.py \
    fix_quotes_for_app_args.py \
    write_cpu_ram_usage.sh \
    execute-gradle.sh \
    execute-git.sh \
    entrypoint.sh \
    /app/
# make scripts executable
RUN chmod +x /app/*.sh
# change the entry point
ENTRYPOINT ["/app/entrypoint.sh"]