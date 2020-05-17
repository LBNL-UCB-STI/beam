FROM ubuntu:20.04

ENV TZ America/New_York
ENV DEBIAN_FRONTEND noninteractive

RUN apt update && apt --yes install tzdata \
        git build-essential cmake python3 \
        libboost-all-dev libcairo2-dev \
        libcgal-dev libproj-dev zlib1g-dev

RUN git clone https://github.com/LBNL-UCB-STI/routing-framework.git && \
    cd routing-framework && git submodule update --init

SHELL ["/bin/bash", "-c"]
WORKDIR routing-framework/External

RUN  cd fast-cpp-csv-parser && cp *.h /usr/local/include && cd .. && \
     cd randomc && mkdir /usr/local/include/randomc && cp *.cpp *.h $_ && cd .. && \
     cd RoutingKit && make && cp -r include lib /usr/local && cd .. && \
     cd stocc && mkdir /usr/local/include/stocc && cp *.cpp *.h $_ && cd .. && \
     cd vectorclass && mkdir /usr/local/include/vectorclass && cp *.h special/* $_ && cd ..

WORKDIR /routing-framework

RUN cmake -S . -B Build/Devel && cmake --build Build/Devel
