FROM openjdk:8
RUN apt-get update
RUN apt-get -y install awscli build-essential
RUN wget -O git-lfs.deb https://packagecloud.io/github/git-lfs/packages/debian/stretch/git-lfs_2.3.4_amd64.deb/download
RUN dpkg -i git-lfs.deb
RUN rm git-lfs.deb
WORKDIR /app
VOLUME /app/sources
VOLUME /app/data
ADD write_cpu_ram_usage.sh /app
RUN chmod +x /app/write_cpu_ram_usage.sh
ADD entrypoint.sh /app
RUN chmod +x /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]