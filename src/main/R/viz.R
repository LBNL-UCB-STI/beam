load.libraries(c('png','rgdal','zoom','RColorBrewer','GISTools','snow','raster','prettymapr','parallel','grid'))

source('/Users/critter/Dropbox/ucb/ucb_smartcities_all/sandbox/colinsheppard/src/main/R/viz-functions.R')

do.or.load('/Users/critter/Dropbox/ucb/vto/viz/base-layers/base-layers.Rdata',function(){})
do.or.load('/Users/critter/Documents/matsim/viz/sites.Rdata',function(){})
do.or.load('/Users/critter/Documents/matsim/viz/network.Rdata',function(){})
do.or.load('/Users/critter/Documents/matsim/viz/agent-data.Rdata',function(){})

###############################################
##### Select driver to focus on and force 
##### competition for the charger
###############################################
downtown.sf <- c(-13630878.25,4545157.57,-13625770.14,4549783.24)
#the.person <- sample(agent.mob.m$person,1) # who do we focus on

# Inspect patterns of agents who pass through downtown SF at any time
join.on(agent.mob.m[person%in%u(agent.mob.m[t==end & x>downtown.sf[1] & x<downtown.sf[3] & y>downtown.sf[2] & y<downtown.sf[4]]$person),list(num.trips=length(u(duration)),hours.of.mobility=diff(range(t))/3600,min.soc=min(soc)),by='person'],ch.events[,list(num.ch.events=max(pair)),by='person'],'person','person','num.ch.events')

# people descrips
# 4516861 drives from sonoma and soc drops to 12% and site is already occupied with a completed charge, can fake making her wait
#
# 569727 makes many trips and charge events and shares a point with 28264 but they don't ever wait for each other

the.person <- 4516861 # 7 trips, 6 ch events, 11 hours of duration
other.person <- 3379798
third.person <- 3589493
center <- c(-13605343 - 30000,4523966+30000) # custom center 

if(F){
  do.or.load('/Users/critter/Documents/matsim/viz/agent-data-1k.Rdata',function(){})
  # fake making the.person wait for 30 minutes
  ch.stats <- ch.events[person== the.person & pair==1 & is.charging==T,list(n.sec=length(t),first.t=t[1],last.t=tail(t,1),first.site=site[1])]
  ch.events[person== the.person & pair==1 & t < ch.stats$first.t + 1800,is.charging:=F]
  ch.events[person== the.person & pair==1 & t >= ch.stats$first.t + 1800 & t < ch.stats$first.t + ch.stats$n.sec + 1800,is.charging:=T]
  ch.events[person== the.person & pair==1,soc:=c(rep(soc[1],1800),head(soc,-1800))]
  last.hr.soc <- tail(ch.events[person== the.person & pair==1 & soc<1]$soc,3600)
  other.person <- ch.events[site==ch.stats$first.site & t==ch.stats$first.t & person!=the.person]$person
  ch.stats[,first.t:=first.t-300]
  ch.events[person== other.person & pair==1 & t < ch.stats$first.t - 1800,is.charging:=F]
  ch.events[person== other.person & pair==1 & t >= ch.stats$first.t - 1800 & t < ch.stats$first.t + 1800,is.charging:=T]
  ch.events[person== other.person & pair==1 & t >= ch.stats$first.t - 1800 & t < ch.stats$first.t + 1800,soc:=last.hr.soc]

  ch.events.1k <- ch.events[person %in% c(the.person,other.person)]
  agent.mob.m.1k <- agent.mob.m[person %in% c(the.person,other.person)]

  rm('ch.events','agent.mob.m')
  do.or.load('/Users/critter/Documents/matsim/viz/agent-data-10k.Rdata',function(){})
  ch.events <- rbindlist(list(ch.events[!person %in% c(the.person,other.person)],ch.events.1k))
  agent.mob.m <- rbindlist(list(agent.mob.m[!person %in% c(the.person,other.person)],agent.mob.m.1k))
  rm('ch.events.1k','agent.mob.m.1k')
  save(ch.events,agent.mob.m,file='/Users/critter/Documents/matsim/viz/agent-data.Rdata')
}
# fake a third driver to arrive, wait, and charge at end of the main driver's session
first.charge.stats <- ch.events[person==the.person & pair==1 & is.charging==T,list(min.t=min(t),max.t=max(t),site=site[1],plug=plug[1])]
cands <- u(ch.events[t==first.charge.stats$min.t & site==first.charge.stats$site & plug==first.charge.stats$plug & !person%in%c(the.person,other.person)]$person)
cand.stats <- ch.events[person%in%cands & pair==1,list(min.t=min(t),max.t=max(t),site=site[1],plug=plug[1],soc=soc[1]),by='person']
ch.events <- ch.events[!(person==third.person & t<=first.charge.stats$min.t+1800)]
ch.events[person==third.person & pair==1 & t >= first.charge.stats$max.t + 300 & t < first.charge.stats$max.t+2100,is.charging:=T]
first.hr.soc <- ch.events[person== the.person & pair==1 & is.charging==T]$soc
ch.events[person==third.person & pair==1 & t >= first.charge.stats$max.t + 300,soc:=first.hr.soc]
ch.events[person==third.person & pair==1,rank:=3]
ch.events[person==third.person & pair==1 & t < first.charge.stats$max.t + 300,soc:=head(first.hr.soc,1)]

# fake the location of the site to avoid the jump between arrival loc and site loc
site.to.move <- ch.events[person==the.person]$site[1]
move.to.x <- tail(agent.mob.m[person==the.person & pair==1],1)$x
move.to.y <- tail(agent.mob.m[person==the.person & pair==1],1)$y + 10
sites[id==site.to.move,':='(x=move.to.x,y=move.to.y)]
ch.events[site==site.to.move,':='(site.x=move.to.x,site.y=move.to.y)]

# fake the timing of charging events to throw them into view
setkey(ch.events,person,pair)
ch.events.small <- unique(ch.events)
end.t <- ch.events.small[person==the.person & pair==1]$t
ch.events.small[,new.t:=t]
ch.events.small[t < end.t,new.t:=round(runif(length(person),43250,53250))] 
ch.events.small[,delta.t:=new.t - t]
ch.events <- join.on(ch.events,ch.events.small,c('person','pair'),c('person','pair'),'delta.t')
ch.events[!person %in% c(the.person,other.person,third.person),t:= t + delta.t]

# the main person needs to keep soc
ch.events[t>=50000 & person==the.person,soc:=1]
# kick everyone out not in the main 3
ch.events[site==260 & plug==400 & !person%in%c(the.person,other.person,third.person),t:=t+1e8]

# turn off everyone we don't want to see
agent.mob.m[,alpha:=1]
agent.mob.m[t <= 31684 & person!=the.person,alpha:=0]
agent.mob.m[t <= 43259 & !person%in%c(the.person,other.person,third.person),alpha:=0]
ch.events[t <= 31684 & person!=the.person,alpha:=0]

###############################################
##### Program the camera
###############################################
time.to.zoom.in <- 400
time.to.stay.zoomed <- 3600*3.2325
time.to.zoom.out <- 2000
zoom.out.form <- c(0,0.05,0.1,0.015,0.2,0.4,0.8,1.6,3,5,10,15,20,25,50,80,90,95,97.5,98.75,99,99.5,99.75,99.9,100)

camera <- copy(agent.mob.m[person==the.person,list(t,x,y)])
all.t <- min(c(camera$t,ch.events$time)):max(c(camera$t,ch.events$time))
camera <- rbindlist(list(camera,data.table(t=all.t[tail(!duplicated(c(camera$t,all.t)),-nrow(camera))])),fill=T)
camera[,z.level:=12]
setkey(camera,t)
first.arrival <- which(is.na(camera$x))[1]
camera[1:floor(first.arrival/2),z.level:=make.spline(head(t,1),tail(t,1),c(18,15,13,12.01))]
camera[floor(first.arrival/2):first.arrival,z.level:=make.spline(head(t,1),tail(t,1),c(12.01,12.1,12.2,12.3,12.5,13,14,15,17))]
camera[(first.arrival+1):(first.arrival+time.to.zoom.in),z.level:=make.spline(head(t,1),tail(t,1),c(17,22,23,24))]
camera[(first.arrival+time.to.zoom.in + 1):(first.arrival+time.to.zoom.in+time.to.stay.zoomed),z.level:=make.spline(head(t,1),tail(t,1),c(24,23.9,23.7,23))]
camera[(first.arrival+time.to.zoom.in+time.to.stay.zoomed + 1):(first.arrival+time.to.zoom.in+time.to.stay.zoomed+time.to.zoom.out),z.level:=make.spline(head(t,1),tail(t,1),c(23,16,13,11.25))]
camera[(first.arrival+time.to.zoom.in+time.to.stay.zoomed+time.to.zoom.out):nrow(camera),z.level:=11.25]

camera[(first.arrival-150):first.arrival,':='(x=seq(camera$x[first.arrival-150],ch.events[person==the.person]$site.x[1],length.out=151),y=seq(camera$y[first.arrival-150],ch.events[person==the.person]$site.y[1],length.out=151))]
camera[(first.arrival+1):(first.arrival+time.to.zoom.in+time.to.stay.zoomed),':='(x=ch.events[person==the.person]$site.x[1],y=ch.events[person==the.person]$site.y[1])]
camera[(first.arrival+time.to.zoom.in+time.to.stay.zoomed+1):(first.arrival+time.to.zoom.in+time.to.stay.zoomed+time.to.zoom.out),':='(
       x=make.spline(head(t,1),tail(t,1),make.knots(ch.events[person==the.person]$site.x[1],center[1],zoom.out.form)),
       y=make.spline(head(t,1),tail(t,1),make.knots(ch.events[person==the.person]$site.y[1],center[2],zoom.out.form)))]
camera[(first.arrival+time.to.zoom.in+time.to.stay.zoomed+time.to.zoom.out):nrow(camera),':='(x=center[1],y=center[2])]

map.bb <- to.bb(bb.to.lims(bbox(map)))

soc.colors <- rev(colorRampPalette(brewer.pal(9,'Reds'))(100))
setkey(link.nodes,type)
setkey(ch.events,t,site)
setkey(agent.mob.m,t)

make.frame <- function(i,dt){
  cam <- camera[i*dt]
  png(pp('/Users/critter/Documents/matsim/ani/img',sprintf('%06d',i),'.png'),width = 1920, height = 1080, units = "px") 
  d.size <- dev.size(units = "cm")/100
  dev.control(displaylist="enable")
  lims <- my.zoom(cam,d.size)
  plot.map(cam,lims)
  zoomplot.zoom(xlim=pinch(lims$xlim),ylim=pinch(lims$ylim,0.85))
  plot.buildings(cam,lims)
  plot.segs(cam,lims)
  vehs.this.sec <- agent.mob.m[J(cam$t)][x>=lims$xlim[1] & x<=lims$xlim[2] & y>=lims$ylim[1] & y<=lims$ylim[2]]
  if(!is.na(vehs.this.sec$person[1])){
    for(vehicle.i in 1:nrow(vehs.this.sec)){
      plot.veh(vehs.this.sec[vehicle.i],cam,d.size,scale=ifelse(i<800,1/30,1/40))
    }
  }
  plot.sites(cam,lims,d.size)
  plot.clock(cam$t,d.size)
  dev.off()
  return(pp('/Users/critter/Documents/matsim/ani/img',sprintf('%06d',i),'.png'))
}

dt <- 5
for(i in seq(1,5000,by=12)){
  make.frame(i,dt)
}

#options(mc.cores=4)
#mclapply(seq(1,nrow(camera),by=20),make.frame, mc.cores = 4)

#system(pp('ffmpeg -framerate 6 -i /Users/critter/Documents/matsim/ani/img%05d.png -vf "scale=640:-1" -c:v libx264 -profile:v high -crf 20 -pix_fmt yuv420p -r 30 /Users/critter/Documents/matsim/ani/beam-animation.mp4'),intern=T,input='Y')
system(pp('ffmpeg -framerate 6 -i /Users/critter/Documents/matsim/ani/img%06d.png -vf "scale=1920:-1" -c:v libx264 -profile:v high -crf 20 -pix_fmt yuv420p -r 60 /Users/critter/Documents/matsim/ani/beam-animation.mp4'),intern=T,input='Y')


# TODO
# -- make details fade in instead of appear (e.g. buildings, locals, etc.)

# To make a geotif:
## transform from EPSG:4326 to EPSG:3857 (this is the Web Mercator project that Mapbox and I believe Google Maps use):
##   http://cs2cs.mygeodata.eu/
## QGis -> Raster -> Georeferencer to choose the points see tutorial:
##   http://www.qgistutorials.com/en/docs/georeferencing_basics.html
## Google Earth in decimal lat/lon, Command-Shift-C to yank the coords
## 
