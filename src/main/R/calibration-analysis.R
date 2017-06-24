
load.libraries(c('sp','maptools','rgdal'))

calib.name <- 'calibration_2017-06-23_16-30-00'
iter.dir <- pp('~/Documents/beam/beam-output/',calib.name,'/ITERS/')

iters <- sort(unlist(lapply(strsplit(list.files(iter.dir),"\\."),function(ll){ as.numeric(ll[2])})))

load.all <- list()
for(the.iter in iters){
  load.file <- pp(iter.dir,'it.',the.iter,'/run0.',the.iter,'.disaggregateLoadProfile.csv')
  dt <- data.table(read.csv(load.file))
  dt[,iter:=the.iter]
  load.all[[length(load.all)+1]] <- dt
}
load.all <- rbindlist(load.all)

ggplot(dt,aes(x=time,y=num.plugged.in,colour=site.type))+geom_bar(stat='identity',position='stack')+facet_wrap(charger.type~spatial.group)
ggplot(load.all[time>=27 & time<=51,list(num.plugged.in=sum(num.plugged.in)),by=c('iter','time','site.type')],aes(x=time,y=num.plugged.in,fill=site.type))+geom_bar(stat='identity',position='stack')+facet_wrap(~iter)
ggplot(load.all[time>=27 & time<=51,list(num.plugged.in=sum(num.plugged.in)),by=c('iter','time')],aes(x=time,y=num.plugged.in))+geom_line()+facet_wrap(~iter)


xy.to.latlon <- function(str){
  x <- as.numeric(strsplit(str,'"')[[1]][2])
  y <- as.numeric(strsplit(str,'"')[[1]][4])
  xy <- data.frame(x=x,y=y)
  xy <- SpatialPoints(xy,proj4string=CRS("+init=epsg:26910"))
  xy <- data.frame(coordinates(spTransform(xy,CRS("+init=epsg:4326"))))
  my.cat(pp(xy$y,',',xy$x))
}
