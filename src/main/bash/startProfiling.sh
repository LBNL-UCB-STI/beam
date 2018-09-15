sudo jcmd RunBeam VM.unlock_commercial_features
sudo cp /home/ubuntu/git/beam/src/main/resources/profiling_heap.jfc /usr/lib/jvm/java-8-oracle/jre/lib/jfr/
sudo jcmd RunBeam JFR.start settings=profiling_heap duration=360s name=Test filename=recording.jfr
