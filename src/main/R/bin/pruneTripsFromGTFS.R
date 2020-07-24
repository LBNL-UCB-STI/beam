#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to delete trips from GTFS data as specified in a csv file provided as input.
#
# The CSV data must contain at least 2 columns titled: agency,trip 
#
# Other columns will be ignored.
#
# The second script argument is the path to the directory containing the GTFS archives. The agency name in the CSV data must match the 
# GTFS archives, (e.g. if agency BART is in the CSV file, there must be an archive titled BART.zip)
# 
# WARNING: this will delete in place the data, so make a backup before using.
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
library(colinmisc)
load.libraries(c('optparse','utils','stringr'),quietly=T)

setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  #args<-c('/Users/critter/Documents/beam/beam-output/experiments/pruning/runs/run.runName_base/plots/transit-trips-below-60-pmt.csv','/Users/critter/Dropbox/ucb/vto/beam-all/beam-octagon/beam/production/application-sfbay/r5-pruned-high')
  args<-c('/Users/critter/Documents/beam/beam-output/experiments/pruning/runs/run.runName_base/plots/transit-trips-below-20-pmt.csv','/Users/critter/Dropbox/ucb/vto/beam-all/beam-octagon/beam/production/application-sfbay/r5-pruned-low')
  args <- parse_args(OptionParser(option_list = option_list,usage = "pruneTripsFromGTFS.R [csv-with-trips-to-delete] [path-to-gtfs-archives]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "pruneTripsFromGTFS.R [csv-with-trips-to-delete] [path-to-gtfs-archives]"),positional_arguments=T)
}
 

######################################################################################################
working.dir <- getwd()
to.prune <- data.table(read.csv(args$args[1]))

the.agency <- to.prune$agency[1]
for(the.agency in u(to.prune$agency)){
  setwd(working.dir)

  file.path <- normalizePath(pp(args$args[2],'/',the.agency,'.zip'))

  if(file.exists(file.path)){
    my.cat(pp('Pruning: ',the.agency))

    tmp.dir <- tempdir()
    unzip(file.path,"stop_times.txt", exdir=tmp.dir)
    unzip(file.path,"trips.txt", exdir=tmp.dir)

    stops <- data.table(read.csv(pp(tmp.dir,'/stop_times.txt'),colClasses='character'))
    trips <- data.table(read.csv(pp(tmp.dir,'/trips.txt'),colClasses='character'))

    stops <- stops[!trip_id %in% to.prune[agency==the.agency]$transitTrip]
    trips <- trips[!trip_id %in% to.prune[agency==the.agency]$transitTrip]

    # Clean up time stamps (remove bad data and zero pad single digit hours)
    stops[arrival_time=='00000000',arrival_time:=' ']
    stops[departure_time=='00000000',departure_time:=' ']
    stops[str_length(str_trim(arrival_time))>0,arrival_time:=str_pad(str_trim(arrival_time),8,pad='0')]
    stops[str_length(str_trim(departure_time))>0,departure_time:=str_pad(str_trim(departure_time),8,pad='0')]
    stops[,stop_id:=str_trim(stop_id)]

    pickup_type.i <- which(names(stops)=='pickup_type')
    stop_sequence.i <- which(names(stops)=='stop_sequence')
    drop_off_type.i <- which(names(stops)=='drop_off_type')
    shape_dist_traveled.i <- which(names(stops)=='shape_dist_traveled')
    stop.inds <- (1:ncol(stops))[-c(pickup_type.i,stop_sequence.i,drop_off_type.i,shape_dist_traveled.i)]
    direction_id.i <- which(names(trips)=='direction_id')
    trip.inds <- (1:ncol(trips))[-direction_id.i]
    
    write.csv(stops,file=pp(tmp.dir,'/stop_times.txt'),na = "",row.names =F,quote=stop.inds)
    write.csv(trips,file=pp(tmp.dir,'/trips.txt'),na = "",row.names =F,quote=trip.inds)

    setwd(tmp.dir)
    zip(file.path,c('stop_times.txt','trips.txt'))
  }else{
    my.cat(pp('Could not prune ',the.agency,', no file found: ',file.path))
  }
}
