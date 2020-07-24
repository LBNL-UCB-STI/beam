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
library(colinmisc)
load.libraries(c('optparse','utils','stringr'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/r5/AC.zip'
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
  if(!is.na(rdur[1]) && rdur[1]==0)rdur[1]<-1
  as.POSIXct(sapply(cumsum(c(0,rev(na.omit(rdur)))),function(x){ x + as.numeric(arrs[1]) }),origin = "1970-01-01")
}
repeat_last = function(x, forward = TRUE, maxgap = Inf, na.rm = FALSE) {
    if (!forward) x = rev(x)           # reverse x twice if carrying backward
    ind = which(!is.na(x))             # get positions of nonmissing values
    if (is.na(x[1]) && !na.rm)         # if it begins with NA
        ind = c(1,ind)                 # add first pos
    rep_times = diff(                  # diffing the indices + length yields how often
        c(ind, length(x) + 1) )          # they need to be repeated
    if (maxgap < Inf) {
        exceed = rep_times - 1 > maxgap  # exceeding maxgap
        if (any(exceed)) {               # any exceed?
            ind = sort(c(ind[exceed] + 1, ind))      # add NA in gaps
            rep_times = diff(c(ind, length(x) + 1) ) # diff again
        }
    }
    x = rep(x[ind], times = rep_times) # repeat the values at these indices
    if (!forward) x = rev(x)           # second reversion
    x
}

working.dir <- getwd()

######################################################################################################
file.path <- args$args[1]
for(file.path in args$args){
  my.cat(pp('Starting: ',file.path))

  setwd(working.dir)
  file.path <- normalizePath(file.path)

  tmp.dir <- tempdir()
  unzip(file.path,"stop_times.txt", exdir=tmp.dir)
  stops <- data.table(read.csv(pp(tmp.dir,'/stop_times.txt'),colClasses='character'))
  stops[,orig.order:=1:nrow(stops)]
  stops.orig <- copy(stops)
  if('timepoint' %in% names(stops)){
    stops <- stops[timepoint=='1']
  }
  stops[str_trim(arrival_time)=='',arrival_time:=NA]
  stops[,arrival_time:=repeat_last(arrival_time),by='trip_id']

  stops[,arrival_str:=''] 
  stops[,arrival_str:=pp(ifelse(as.numeric(substr(arrival_time,0,str_locate(arrival_time,':')[,'start']-1))>23,'1970-01-02 ','1970-01-01 '),as.numeric(substr(arrival_time,0,str_locate(arrival_time,':')[,'start']-1))%%24,substr(arrival_time,str_locate(arrival_time,':')[,'start'],str_length(arrival_time)))]
  stops[,arrival:=to.posix(arrival_str,'%Y-%m-%d %H:%M:%S')]
  stops[,duration:=as.numeric(c(NA,diff(arrival))),by='trip_id']

  bad.trips <- u(stops[duration==0]$trip_id)
  if(length(bad.trips)>0){
    my.cat(pp('Repairing ',length(bad.trips),' trips'))
    stops[trip_id %in% bad.trips,arrival.fixed:=repair.arrival(arrival,duration),by='trip_id']
    stops[,arrival.fixed.str:=pp(ifelse(yday(arrival.fixed)>1,hour(arrival.fixed)+24,str_pad(hour(arrival.fixed),2,pad='0')),":",strftime(arrival.fixed,"%M:%S"))]

    stops[!is.na(arrival.fixed),departure_time:=arrival.fixed.str]
    stops[!is.na(arrival.fixed),arrival_time:=arrival.fixed.str]

    stops.final <- join.on(stops.orig,stops,'orig.order','orig.order',c('arrival_time','departure_time'),'fix.')
    stops.final[,':='(arrival_time=fix.arrival_time,departure_time=fix.departure_time)]
    setkey(stops.final,orig.order)
    stops.final[,':='(fix.arrival_time=NULL,fix.departure_time=NULL,orig.order=NULL)]

    if('stop_headsign' %in% names(stops.final)){
      all.names <- names(stops.final)
      streval(pp("write.csv(stops.final[,.(trip_id,arrival_time,departure_time,stop_id, stop_headsign,",pp(all.names[-which(all.names%in%c('trip_id','arrival_time','departure_time','stop_id','stop_headsign'))],collapse=","),")],file=pp(tmp.dir,'/stop_times.txt'),na = '',row.names =F,quote=c(1:5))"))
    }else{
      write.csv(stops.final,file=pp(tmp.dir,'/stop_times.txt'),na = "",row.names =F,quote=c(1:4))
    }
    
    setwd(tmp.dir)
    zip(file.path,'stop_times.txt')
  }else{
    my.cat('No repairs needed')
  }

  my.cat(pp('Completed: ',file.path))
}
