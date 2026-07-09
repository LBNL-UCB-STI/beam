FROM openjdk:8

RUN wget --quiet https://media.githubusercontent.com/media/kiranchhatre/beam/mnl-inspired/src/main/python/calibration/BEAMPyOpt/Env_BEAMPyOpt.txt

ENV PATH /opt/conda/bin:$PATH

RUN apt-get update --fix-missing && \
    apt-get install -y wget bzip2 ca-certificates libglib2.0-0 libxext6 libsm6 libxrender1 git mercurial subversion && \
    apt-get clean

RUN wget --quiet https://repo.anaconda.com/archive/Anaconda3-2020.11-Linux-x86_64.sh -O ~/anaconda.sh && \
    /bin/bash ~/anaconda.sh -b -p /opt/conda && \
    rm ~/anaconda.sh && \
    ln -s /opt/conda/etc/profile.d/conda.sh /etc/profile.d/conda.sh && \
    conda create --name beamenv --file Env_BEAMPyOpt.txt &&\
    echo ". /opt/conda/etc/profile.d/conda.sh" >> ~/.bashrc && \
    echo "conda activate beamenv" >> ~/.bashrc && \
    find /opt/conda/ -follow -type f -name '*.a' -delete && \
    find /opt/conda/ -follow -type f -name '*.js.map' -delete && \
    /opt/conda/bin/conda clean -afy

RUN conda install pandas
RUN conda install natsort

COPY ./scripts /opt/calibration

RUN apt-get install git
RUN git clone https://github.com/LBNL-UCB-STI/beam.git
WORKDIR /opt/calibration
RUN chmod +x run_SSC.sh
ENTRYPOINT ["bash", "run_SSC.sh"]