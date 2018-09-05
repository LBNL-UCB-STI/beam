# fitler outputs for ride hailing summary and analysis

load("/Users/critter/Documents/beam/beam-output/sf-light-25k__2018-09-04_16-19-41/ITERS/it.0/0.events.Rdata")

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


my.cat(pp('# trips: ',nrow(rh)))
my.cat(pp('# vehicles: ',length(u(rh$vehicle))))
my.cat(pp('unique passengers: ',length(u(enters$person))))
my.cat(pp('Total VMT: ',sum(rh$length)/1608))
my.cat(pp('VMT per vehicle: ',sum(rh$length)/1608/length(u(rh$vehicle))))
my.cat(pp('Avg trip length with passenger: ',sum(rh[num_passengers>0]$length/nrow(rh[num_passengers>0]))/1608))
my.cat(pp('Avg deadhead trip length: ',sum(rh[num_passengers==0 & reposition==F]$length/nrow(rh[num_passengers==0 & reposition==F]))/1608))
my.cat(pp('Avg reposition trip length: ',sum(rh[num_passengers==0 & reposition==T]$length/nrow(rh[num_passengers==0 & reposition==T]))/1608))
my.cat(pp('Empty VMT fraction: ',sum(rh[num_passengers==0]$length)/sum(rh$length)))
my.cat(pp('Deadhead VMT fraction: ',sum(rh[num_passengers==0 & reposition==F]$length)/sum(rh$length)))
my.cat(pp('Reposition VMT fraction: ',sum(rh[num_passengers==0 & reposition==T]$length)/sum(rh$length)))
my.cat(pp('Avg. Speed: ',mean(rh$speed)))
my.cat(pp('Avg. Speed VMT weighted: ',weighted.mean(rh$speed,rh$length)))


num.paths <- rh[,.(n=length(speed),frac.repos=sum(num_passengers==0)/sum(num_passengers==1)),by='vehicle']
num.paths[n==max(num.paths$n)]
rh[vehicle==num.paths[frac.repos < Inf & frac.repos>1.2]$vehicle[1]]

busy <- rh[vehicle==num.paths[n==max(num.paths$n)]$vehicle[1]]

#ggplot(rh[vehicle==num.paths[frac.repos < Inf & frac.repos>1.1]$vehicle[12]],aes(x=start.x,y=start.y,colour=factor(time)))+geom_point()

rh[,type:='Movement']
ref[,type:='Charge']
both <- rbindlist(list(rh,ref),use.names=T,fill=T)
setkey(both,time)

#ggplot(rh,aes(x= start.x,y=start.y,xend=end.x,yend=end.y,colour=reposition))+geom_segment()
ggplot(rh,aes(x= start.x,y=start.y,colour=kwh))+geom_point()+geom_point(data=both[type=='Charge'])

write.csv(both,file='/Users/critter/Documents/beam/beam-output/sf-light-25k__2018-09-04_15-56-02/ITERS/it.0/beam-ev-ride-hail.csv',row.names=F)

# find a veh that recharges
both[vehicle%in%u(both[type=='Charge']$vehicle),.N,by='vehicle']
