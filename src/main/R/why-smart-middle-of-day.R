

load(file='/Users/critter/Documents/beam/input/sf-bay-sampled-plans-multi-day-10.Rdata')

new.plans[,orig.i:=1:nrow(new.plans)]
setkey(new.plans,id,end_time)

new.plans[,time.in.activity:=c(NA,diff(end_time)),by=c('id')]

by.hour <- new.plans[!is.na(time.in.activity)]
by.hour <- by.hour[,.(type=type,id=id,x=x,y=y,t=seq(end_time-time.in.activity*3600,end_time,by=3600)),by='orig.i']

by.hour[,hr:=hour(t)]

by.hour.sum <- by.hour[,.(n=length(id)),by=c('type','hr')]

save(by.hour.sum,file='/Users/critter/Documents/beam/beam-output/calibration/acivity-by-hour-summary.Rdata')

setkey(by.hour.sum,hr,type)
ggplot(by.hour.sum,aes(x=hr,y=n,fill=type))+geom_bar(stat='identity')
