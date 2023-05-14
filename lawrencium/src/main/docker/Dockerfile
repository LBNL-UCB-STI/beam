FROM openjdk:8
RUN apt-get update
RUN wget -O git-lfs.deb https://packagecloud.io/github/git-lfs/packages/debian/stretch/git-lfs_2.3.4_amd64.deb/download
RUN dpkg -i git-lfs.deb && rm git-lfs.deb
RUN apt-get -y install awscli build-essential python3 python3-pandas
RUN pip install plotly kaleido psutil
WORKDIR /app
VOLUME /app/sources
VOLUME /app/data
ADD write_cpu_ram_usage.sh /app
RUN chmod +x /app/write_cpu_ram_usage.sh
ADD entrypoint.sh /app
RUN chmod +x /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]