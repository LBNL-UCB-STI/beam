# fitler outputs for ride hailing summary and analysis

out.scen <- 'EVFleet__2018-09-05_20-52-48'
out.scen <- 'broken-rhm'

load(pp("/Users/critter/Documents/beam/beam-output/EVFleet-Final/",out.scen,"/ITERS/it.0/0.events.Rdata"))

pt <- df[type=='PathTraversal' & vehicle_type=='BEV' & substr(vehicle,1,5)=='rideH']

rh <- pt[,.(time=departure_time,duration=(arrival_time-departure_time),vehicle,num_passengers,length,start.x,start.y,end.x,end.y,kwh=fuel/3.6e6)]
rh[duration==0,duration:=1]
rh[,speed:=length/1609/(duration/3600)]

ref <- df[type=='RefuelEvent',.(time,duration=duration,vehicle,num_passengers=NA,length=NA,start.x=location.x,start.y=location.y,end.x=NA,end.y=NA,kwh=fuel/3.6e6)]

# filter out weird trips with bad coords and 0 length empty vehicle movements
rh <- rh[start.x < -100]
rh <- rh[length > 0 | num_passengers>0]

detect.repos <- function(n){
 df <- data.table(n2=c(n,NA),n=c(NA,n))
 df[,res:=F]
 df[n==0&n2==0,res:=T]
 tail(df$res,-1)
}
rh[,reposition:=detect.repos(num_passengers),by='vehicle']

#write.csv(rh,file='/Users/critter/Downloads/output 3/application-sfbay/base__2018-06-18_16-21-35/ITERS/it.1/beam-ride-hail-base-2018-06-20.csv')

enters <- df[type=='PersonEntersVehicle'  & substr(vehicle,1,5)=='rideH' & substr(person,1,5)!='rideH',.(person,vehicle)]
length(u(enters$person))

# Summary
# 28,378 vehicles
# 620,757 trips
# 253,623 unique passengers served
# Total VMT = 4,578,719
# Avg VMT per vehicle = 161
# Avg length of trip with passenger = 6.3 miles
# Avg length of dead head trip = 1 mile
# Deadheading = 14.35%  of total VMT
# Avg speed 22 mph

summ.str <- ''
summ.str <- pp(summ.str,'\n','# trips:\t\t',nrow(rh))
summ.str <- pp(summ.str,'\n','# vehicles:\t\t',length(u(rh$vehicle)))
summ.str <- pp(summ.str,'\n','unique passengers:\t\t',length(u(enters$person)))
summ.str <- pp(summ.str,'\n','Total VMT:\t\t',sum(rh$length)/1608)
summ.str <- pp(summ.str,'\n','VMT per vehicle:\t\t',sum(rh$length)/1608/length(u(rh$vehicle)))
summ.str <- pp(summ.str,'\n','Avg trip length with passenger:\t\t',sum(rh[num_passengers>0]$length/nrow(rh[num_passengers>0]))/1608)
summ.str <- pp(summ.str,'\n','Avg deadhead trip length:\t\t',sum(rh[num_passengers==0 & reposition==F]$length/nrow(rh[num_passengers==0 & reposition==F]))/1608)
summ.str <- pp(summ.str,'\n','Avg reposition trip length:\t\t',sum(rh[num_passengers==0 & reposition==T]$length/nrow(rh[num_passengers==0 & reposition==T]))/1608)
summ.str <- pp(summ.str,'\n','Empty VMT fraction:\t\t',sum(rh[num_passengers==0]$length)/sum(rh$length))
summ.str <- pp(summ.str,'\n','Deadhead VMT fraction:\t\t',sum(rh[num_passengers==0 & reposition==F]$length)/sum(rh$length))
summ.str <- pp(summ.str,'\n','Reposition VMT fraction:\t\t',sum(rh[num_passengers==0 & reposition==T]$length)/sum(rh$length))
summ.str <- pp(summ.str,'\n','Avg. Speed:\t\t',mean(rh$speed))
summ.str <- pp(summ.str,'\n','Avg. Speed VMT weighted:\t\t',weighted.mean(rh$speed,rh$length))
my.cat(summ.str)


num.paths <- rh[,.(n=length(speed),frac.repos=sum(num_passengers==0)/sum(num_passengers==1)),by='vehicle']
num.paths[n==max(num.paths$n)]
rh[vehicle==num.paths[frac.repos < Inf & frac.repos>1.2]$vehicle[1]]

busy <- rh[vehicle==num.paths[n==max(num.paths$n)]$vehicle[1]]

#ggplot(rh[vehicle==num.paths[frac.repos < Inf & frac.repos>1.1]$vehicle[12]],aes(x=start.x,y=start.y,colour=factor(time)))+geom_point()

rh[,type:='Movement']
ref[,type:='Charge']
both <- rbindlist(list(rh,ref),use.names=T,fill=T)
setkey(both,time)
both[,hour:=floor(time/3600)]

#ggplot(rh,aes(x= start.x,y=start.y,xend=end.x,yend=end.y,colour=reposition))+geom_segment()
#ggplot(rh,aes(x= start.x,y=start.y,colour=kwh))+geom_point()+geom_point(data=both[type=='Charge'])
ggplot(both[type=='Movement'],aes(x= start.x,y=start.y,colour=type))+geom_point(alpha=0.5)+geom_point(data=both[type=='Charge'],size=0.25)
dev.new();ggplot(both[type=='Movement'],aes(x= start.x,y=start.y,colour=type))+geom_point(alpha=0.5)+geom_point(data=both[type=='Charge'],size=0.25)+facet_wrap(~hour)

write.csv(both,file=pp("/Users/critter/Documents/beam/beam-output/EVFleet-Final/",out.scen,"/ITERS/it.0/beam-ev-ride-hail.csv"),row.names=F)

# find a veh that recharges
both[vehicle%in%u(both[type=='Charge']$vehicle),.N,by='vehicle']


# Compare RH initial positions to activities
rhi<-data.table(read.csv(pp("/Users/critter/Documents/beam/beam-output/EVFleet-Final/",out.scen,"/ITERS/it.0/0.rideHailInitialLocation.csv")))
rhi[,':='(x=xCoord,y=yCoord)]
rhi[,type:='RH-Initial']
load('/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/population.Rdata')
pop <- df
rm('df')
poprh<-rbindlist(list(pop,rhi),use.names=T,fill=T)
ggplot(poprh,aes(x=x,y=y,colour=type))+geom_point(alpha=.2)
