FROM mambaorg/micromamba:1.5.3
# using root to install required packages
USER root
RUN apt-get update && apt-get install -y ca-certificates curl gnupg
RUN install -m 0755 -d /etc/apt/keyrings
RUN curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
RUN chmod a+r /etc/apt/keyrings/docker.gpg
RUN echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
RUN apt-get update
RUN apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
# adding sudo and giving mamba user sudo privileges
RUN apt-get install -y sudo vim tmux wget curl git unzip
# installing correct version of git lfs
RUN wget -O git-lfs.deb https://packagecloud.io/github/git-lfs/packages/debian/stretch/git-lfs_2.3.4_amd64.deb/download && \
    dpkg -i git-lfs.deb && \
    rm git-lfs.deb
# to have a lot of permissions
RUN adduser $MAMBA_USER sudo
RUN usermod -aG docker $MAMBA_USER
#RUN usermod -aG sudo $MAMBA_USER
RUN echo "$MAMBA_USER ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
# using mamba user in order to utilyze micromamba python environment
USER $MAMBA_USER
COPY --chown=$MAMBA_USER:$MAMBA_USER dependency.yaml /tmp/dependency.yaml
RUN micromamba install -y -n base -f /tmp/dependency.yaml && \
    micromamba clean --all --yes
# using /app as working directory
WORKDIR /app
RUN chown $MAMBA_USER:$MAMBA_USER /app
# a script to test the docker from python is working
COPY --chown=$MAMBA_USER:$MAMBA_USER run_hello_world_docker.py /misc/run_hello_world_docker.py
# a script to pull images described in the settings.yaml file
COPY --chown=$MAMBA_USER:$MAMBA_USER pull_images_from_settings_yaml.py /misc/pull_images_from_settings_yaml.py
# adding additional scripts
COPY --chown=$MAMBA_USER:$MAMBA_USER run_docker_and_execute_pilates.sh /usr/local/bin/run_docker_and_execute_pilates.sh
COPY --chown=$MAMBA_USER:$MAMBA_USER second_entrypoint.sh /usr/local/bin/second_entrypoint.sh
RUN chmod +x /usr/local/bin/run_docker_and_execute_pilates.sh
RUN chmod +x /usr/local/bin/second_entrypoint.sh
ENTRYPOINT ["/usr/local/bin/_entrypoint.sh", "/usr/local/bin/second_entrypoint.sh"]