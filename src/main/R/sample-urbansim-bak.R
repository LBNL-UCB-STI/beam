
library(colinmisc)

setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
load.libraries(c('optparse','stringr'),quietly=T)

in.dir <- '/Users/critter/Dropbox/ucb/vto/beam-colin/urbansim/urbansim-sfbay/'
out.dir <- '/Users/critter/Dropbox/ucb/vto/beam-colin/urbansim/urbansim-sf/'

build <- csv2rdata(pp(in.dir,'buildings.csv'))
hh <- csv2rdata(pp(in.dir,'households.csv'))
per <- csv2rdata(pp(in.dir,'persons.csv'))
parcel <- csv2rdata(pp(in.dir,'parcel_attr.csv'))
units <- csv2rdata(pp(in.dir,'units.csv'))
plans <- csv2rdata(pp(in.dir,'plans.csv'))

# join unit location to the household 
build <- join.on(build,parcel,'parcel_id','primary_id',c('x','y'))
hh <- join.on(hh,build,'building_id','building_id',c('x','y'))
hh <- hh[!is.na(x)]

bbox.ur <- c(-122.379394,37.813043)
bbox.ll <- c(-122.521046,37.708690)

hh.bound <- hh[x>bbox.ll[1] & y>bbox.ll[2] & x<bbox.ur[1] & y<bbox.ur[2]]

samp.sizes <- c(1e3,10e3,100e3,250e3)

samp.size <- samp.sizes[1]
for(samp.size in samp.sizes){
  my.cat(samp.size)
  
  hh.samp <- hh.bound[sample(nrow(hh.bound),samp.size)]
  per.samp <- per[household_id %in% hh.samp$household_id]
  plan.samp <- plans[personId %in% per.samp$person_id]
  build.samp <- build[building_id %in% u(hh.samp$building_id)]
  units.samp <- units[unit_id %in% u(hh.samp$unit_id)]
  parcel.samp <- parcel[primary_id %in% u(build.samp$parcel_id)]

  # Temp synthetic plans until we get functioning plans from US team
  all.plans <- list()
  for(perid in per.samp$person_id){
    template <- plans[personId == sample(plans$personId,1)]
    hh.loc <- hh.samp[household_id == per.samp[person_id == perid]$household_id,.(x,y)]
    theplan <- copy(template)
    work.loc <- runif(2,unlist(bbox.ll),unlist(bbox.ur))
    theplan[,x:=c(hh.loc$x,NA,work.loc[1],NA,hh.loc$x)]
    theplan[,y:=c(hh.loc$y,NA,work.loc[2],NA,hh.loc$y)]
    theplan[,personId:=perid]
    all.plans[[length(all.plans)+1]] <- theplan
  }
  plan.samp <- rbindlist(all.plans)
  plan.samp[,mode:='']

  out.dir.samp <- pp(out.dir,'/',samp.size/1e3,'k/')
  make.dir(out.dir.samp)
  save(hh.samp,per.samp,plan.samp,build.samp,units.samp,parcel.samp,file=pp(out.dir.samp,'urbansim-input.Rdata'))
  write.csv(hh.samp,file=pp(out.dir.samp,'households.csv'),row.names=F,na='')
  write.csv(per.samp,file=pp(out.dir.samp,'persons.csv'),row.names=F,na='')
  write.csv(plan.samp,file=pp(out.dir.samp,'plans.csv'),row.names=F,na='')
  write.csv(build.samp,file=pp(out.dir.samp,'buildings.csv'),row.names=F,na='')
  write.csv(units.samp,file=pp(out.dir.samp,'units.csv'),row.names=F,na='')
  write.csv(parcel.samp,file=pp(out.dir.samp,'parcel_attr.csv'),row.names=F,na='')
}

