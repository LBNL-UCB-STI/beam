FROM openjdk:8
RUN apt-get update
RUN wget -O git-lfs.deb https://packagecloud.io/github/git-lfs/packages/debian/stretch/git-lfs_2.3.4_amd64.deb/download
RUN dpkg -i git-lfs.deb && rm git-lfs.deb
RUN apt-get -y install awscli build-essential python3 python3-pandas
RUN pip install plotly kaleido psutil
WORKDIR /app
RUN bash -c 'mkdir -p /app/{git,data,common,sources,gradle_cache}'
RUN touch /app/git/.this_volume_was_not_mounted.txt
RUN touch /app/gradle_cache/.this_volume_was_not_mounted.txt
ADD replace_config_path_in_args.py \
    fix_quotes_for_app_args.py \
    write_cpu_ram_usage.sh \
    execute-gradle.sh \
    execute-git.sh \
    execute-sh.sh \
    entrypoint.sh \
    /app/
RUN chmod +x /app/*.sh
ENTRYPOINT ["/app/entrypoint.sh"]