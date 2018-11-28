
load("/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/population.Rdata")

reproj(df,'epsg:26910','epsg:4326')->newdt

newdt[,time:=ifelse(end.time>=0,end.time,tail(end.time,2)[1]+10*3600),by='id']


write.csv(newdt[,.(x=new.x,y=new.y,time,type)],file='/Users/critter/Dropbox/ucb/vto/beam-colin/viz-2018/activities.csv')

popsamp <- sample(newdt$id,100000)
write.csv(newdt[id%in%popsamp,.(x=new.x,y=new.y,time,type)],file='/Users/critter/Dropbox/ucb/vto/beam-colin/viz-2018/activities-sm.csv')


# Now viz movements

# load an events file

pt <- df[type=='PathTraversal' & length>0]
pt[substr(vehicle,0,5)=='rideH',mode:='ride_hail']
setkey(pt,mode)

write.csv(pt[,.(mode,start.x,start.y,end.x,end.y, time=departure_time,num= num_passengers)],file=pp('/Users/critter/Dropbox/ucb/vto/beam-colin/viz-2018/pt.csv'),row.names=F)

for(the.mode in u(pt$mode)){
  write.csv(pt[J(the.mode),.(start.x,start.y,end.x,end.y, time=departure_time+.01,num= num_passengers+0.1)],file=pp('/Users/critter/Dropbox/ucb/vto/beam-colin/viz-2018/pt-',the.mode,'.csv'),row.names=F)
}

# do walk without bushwacking
the.mode<-'walk'
write.csv(pt[J(the.mode)][links!="",.(start.x,start.y,end.x,end.y, time=departure_time+.01,num= num_passengers+0.1)],file=pp('/Users/critter/Dropbox/ucb/vto/beam-colin/viz-2018/pt-',the.mode,'.csv'),row.names=F)

