load.libraries(c('maptools'))

vmt <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/evmt-data.csv'))
vmt[,key:=pp(make,' ',model)]

ggplot(melt(vmt,measure.vars=c('vmt','evmt')),aes(x=erange,y=value,colour=key,shape=variable))+geom_point()+facet_wrap(~type,scales='free_x')


# load plans from multi-day-plans.R

load('/Users/critter/Documents/beam/input/run0-201-leg-data.Rdata')
load('/Users/critter/Documents/beam/input/run0-201-plans-all.Rdata')

plans[,act.end:=to.posix(end,'%H:%M:%S')]
plans[,act.end.hr:=hour(act.end)]
plans[,type:=factor(type)]
plans[,end:=as.character(end)]
plans[,end.dt:=to.posix(pp('1970-01-',ifelse(end=='','10 00:00:00', pp('01 ',end))),'%Y-%m-%d %H:%M:%S')]
setkey(plans,id,end.dt)
plans[,element.i:=1:length(x),by='id']
legs[,dep.dt:=to.posix(pp('1970-01-01 ',dep.time),'%Y-%m-%d %H:%M:%S')]
legs[,id:=person]
setkey(legs,id,dep.dt)
legs[,element.i:=1:length(dist),by='id']
legs <- join.on(legs,plans,c('id','element.i'),c('id','element.i'),c('end.dt','link.id','link.x','link.y'))
legs[,dep.delay:=dep.dt - end.dt]

# Now just deal with subset of plans data we're intersted in
load('/Users/critter/Documents/beam/input/sf-bay-sampled-plans.Rdata')

plans <- plans[id%in%sampled.reg$smart.id]
legs <- legs[id%in%sampled.reg$smart.id]


plans[,xy:=pp(x,' ',y)]
xys <- data.table(xy=u(plans$xy),ll=sapply(u(plans$xy), xy.to.latlon,print=F))

plans <- join.on(plans,xys,'xy','xy','ll')

#save(plans,file='/Users/critter/Documents/beam/input/plans-for-vmt-analysis.Rdata')


load('/Users/critter/Documents/beam/input/run0-201-leg-data.Rdata')
load('/Users/critter/Documents/beam/input/plans-for-vmt-analysis.Rdata')

ev[type=='travelled',.(miles=sum(as.numeric(distance))*0.00061/(max(time)/3600/24)),by='person']->vmt.from.ev
join.on(vmt.from.ev,legs[,.(miles=sum(miles)),by='person'],'person','person','miles','plans.')->vmt.from.both

ggplot(vmt.from.both,aes(x=plans.miles,y=miles))+geom_point()


## Final assumptions used in PLEXOS analysis
#
# Assumptions:
# 
# -- Prius are 30 mile range & 6K
# -- Fords are 30 mile range & 6K
# -- Volt is 55 mile range & 10K 

# BEV adjust to 11K

