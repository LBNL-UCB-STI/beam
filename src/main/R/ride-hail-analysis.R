
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
library('stringr')

all <- list()
for(scen in c('cav','1x','1.5x','2x','3x','4x')){
  df <- csv2rdata(pp('/Users/critter/Documents/beam/beam-output/urbansim-10k-',scen,'/ITERS/it.3/3.events.csv.gz'))

  mc <- df[type=='ModeChoice']
  enter <- df[type=='PersonEntersVehicle']

  mc.rh <- mc[mode%in%c('ride_hail','ride_hail_pooled','ride_hail_transit')]
  enter.rh <- enter[person %in% mc.rh$person & substr(vehicle,1,5)=='rideH']

  mc.rh[,i:=1:(.N),by='person']
  enter.rh[,i:=1:(.N),by='person']

  wait <- join.on(mc.rh,enter.rh,c('person','i'),c('person','i'),c('time','vehicle'),'enter.')
  wait[,wait.time:=enter.time-time]
  wait[,scen:=scen]
  all[[length(all)+1]] <- wait
}
all <- rbindlist(all)

all[wait.time<0,wait.time:=NA]

ggplot(all,aes(x=wait.time/60))+geom_histogram()+facet_wrap(~scen)

