#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to repair bad GTFS data that contains 0 duration trips
#
# This script will take sequences of stops within GTFS trips and if it detects that any arrival -> arrival times in the sequences are the same
# (i.e. a duration 0 movement) then it will look for the next, non-zero difference in arrival time and redistribute that time back among the
# zero-length stop-to-stop legs.
# 
# One gotcha here, this will repair in place the data, so make a backup before using.
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
load.libraries(c('optparse','utils','stringr'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Dropbox/ucb/vto/beam-colin/debugging/SF.zip'
  args <- parse_args(OptionParser(option_list = option_list,usage = "repairStopTimes.R [archives-to-repair]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "repairStopTimes.R [archives-to-repair]"),positional_arguments=T)
}
 
repair.arrival <- function(arrs,durs){
  if(length(arrs)==1)return(arrs[1])
  rdur <- rev(durs)
  inds <- c(which(rdur>0),length(rdur))
  for(i in 1:(length(inds)-1)){
    amount <- floor(rdur[inds[i]]/(inds[i+1]-inds[i]))
    remainder <- rdur[inds[i]] - amount*(inds[i+1]-inds[i])
    rdur[inds[i]:(inds[i+1]-1)] <- c(rep(amount,inds[i+1]-inds[i]-1),amount+remainder)
  }
  as.POSIXct(sapply(cumsum(c(0,rev(na.omit(rdur)))),function(x){ x + as.numeric(arrs[1]) }),origin = "1970-01-01")
}

######################################################################################################
file.path <- args$args[1]
for(file.path in args$args){
  tmp.dir <- tempdir()
  unzip(file.path,"stop_times.txt", exdir=tmp.dir)
  stops <- data.table(read.csv(pp(tmp.dir,'/stop_times.txt')))

  stops[,arrival_str:=pp(ifelse(as.numeric(substr(arrival_time,0,2))>23,'1970-01-02 ','1970-01-01 '),as.numeric(substr(arrival_time,0,2))%%24,substr(arrival_time,3,9))]
  stops[,arrival:=to.posix(arrival_str,'%Y-%m-%d %H:%M:%S')]
  stops[,duration:=as.numeric(c(NA,diff(arrival))),by='trip_id']

  bad.trips <- u(stops[duration==0]$trip_id)
  stops[trip_id %in% bad.trips,arrival.fixed:=repair.arrival(arrival,duration),by='trip_id']
  stops[,arrival.fixed.str:=pp(ifelse(yday(arrival.fixed)>1,hour(arrival.fixed)+24,str_pad(hour(arrival.fixed),2,pad='0')),":",strftime(arrival.fixed,"%M:%S"))]

  stops[!is.na(arrival.fixed),departure_time:=arrival.fixed.str]
  stops[!is.na(arrival.fixed),arrival_time:=arrival.fixed.str]
  stops[,':='(arrival_str=NULL,arrival=NULL,duration=NULL,arrival.fixed=NULL,arrival.fixed.str=NULL)]

  write.csv(stops,file=pp(tmp.dir,'/stop_times.txt'),na = " ",row.names =F,quote=F)
  setwd(tmp.dir)
  zip(file.path,'stop_times.txt')

  my.cat(pp('Completed: ',file.path))
}
