FROM beammodel/miniconda3
RUN /opt/conda/bin/conda config --add channels conda-forge
RUN /opt/conda/bin/conda config --set channel_priority strict
RUN /opt/conda/bin/conda update -qy conda
RUN /opt/conda/bin/conda install -qy numpy pandas matplotlib plotly psutil requests python-kaleido plotly-orca
RUN apt-get update && apt-get -y install zip awscli
RUN mkdir -p /app/sources
WORKDIR /app
RUN wget -O git-lfs.deb https://packagecloud.io/github/git-lfs/packages/debian/stretch/git-lfs_2.3.4_amd64.deb/download
RUN dpkg -i git-lfs.deb
RUN rm git-lfs.deb
VOLUME /app/sources
ADD entrypoint.sh /app
RUN chmod +x /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]
