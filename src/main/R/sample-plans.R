
load.libraries(c('XML','rgdal','maptools','sp','flexclust'))

counties <- readShapePoly(pp(matsim.shared,'/spatial-data/ca-counties/ca-counties.shp'),proj4string=CRS("+proj=longlat +datum=WGS84"))
zips <- readShapePoly(pp(matsim.shared,'/spatial-data/ca-zips/sf-bay-area-zips.shp'),proj4string=CRS("+proj=longlat +datum=WGS84"))
sf.county.inds <- counties$NAME %in% c('Alameda','San Mateo','Sonoma','Marin','Contra Costa','Napa','Solano','Santa Clara','San Francisco')
zips.in.sf <- which(!is.na(over(zips,counties[sf.county.inds,])$NAME))
zips.not.in.sf <- as.numeric(as.character(zips$ZCTA5CE10[which(is.na(over(zips,counties[sf.county.inds,])$NAME))]))

sf.zips <- spTransform(zips[zips.in.sf,],CRS("+init=epsg:26910"))
sf.counties <- spTransform(counties[sf.county.inds,],CRS("+init=epsg:26910"))

# Visualize for posterity
#plot(zips[zips.in.sf,])
#plot(counties[sf.county.inds,],add=T,col='#99000066')


# Pull home location out of the full set of plans

#do.or.load('/Users/critter/Documents/matsim/input/run0-201-plans-homes.Rdata',function(){
  #plans.xml <- xmlParse('/Users/critter/Documents/matsim/input/run0.201.plans.xml')
  ##plans.xml <- xmlParse('/Users/critter/Documents/matsim/input/run0.201.plans.sample-1k.xml')
  ##plans.xml <- xmlParse('/Users/critter/Documents/matsim/input/run0.201.plans.sample-3.xml')

  #homes <- data.table(t(xpathSApply(plans.xml,'/population/person',function(person){ 
    #id <- as.numeric(xmlGetAttr(person,'id'))
    #i.selected <- which(xpathSApply(person,'./plan',xmlGetAttr,'selected') == 'yes')[1]
    #act.types <- xpathSApply(person[[i.selected]],'./act',xmlGetAttr,'type')
    #i.home <- which(act.types=='Home')[1]
    #data.frame(id=id,x=as.numeric(xmlGetAttr(person[[i.selected]][[i.home]],'x')),
                                  #y=as.numeric(xmlGetAttr(person[[i.selected]][[i.home]],'y')))
  #})))
  #return(list('homes'=homes))
#})

#save(homes,'/Users/critter/Documents/matsim/input/run0-201-plans-homes.Rdata')

# Alternative method using UNIX

# cd ~/Documents/matsim/input
#cat run0.201.plans.xml | grep "person \|plan \|act type=\"Home" > run0.201.plans.thinned.xml
#cat run0.201.plans.thinned.xml | grep -A 2 person | grep -v plan | grep -v "\-\-" > run0.201.plans.thinned.2.xml
#cat run0.201.plans.thinned.2.xml | sed 's/.*\<person id="//' | sed 's/" employed="yes"\>//' | sed 's/.*x="//' | sed 's/" y="/,/' | sed 's/".*//' > run0.201.plans.thinned.3.csv

# Grab all activity, not just homes....

# cd ~/Documents/matsim/input
#cat run0.201.plans.xml | grep "person \|plan\|act type=" > /Users/critter/Documents/matsim/input/run0.201.plans.thinned.xml
#awk '/person id=/ || / selected="yes"/,/\/plan/ ' /Users/critter/Documents/matsim/input/run0.201.plans.thinned.xml | awk '/person/{flag=1}/ selected="no"/{flag=0}/\/plan/{flag++; if(flag==1)next }flag' > /Users/critter/Documents/#matsim/input/run0.201.plans.thinned2.xml
#grep -v plan run0.201.plans.thinned2.xml > run0.201.plans.thinned3.xml
#cat run0.201.plans.thinned3.xml | sed 's/.*\<person id="//' | sed 's/" employed="yes"\>//' | sed 's/.*type="//'  | sed 's/" link="sfpt/,/' | sed 's/" x="/,/' | sed 's/" y="/,/' | sed 's/" end_time="/,/' | sed 's/".*//' > run0.201.plans.thinned4.csv

##################################
# Grab data from legs/routes
##################################
# First focus in on selected:
# cat run0.201.plans.xml | awk '/person/{flag=1}/ selected="no"/{flag=0}/\/plan/{flag++; if(flag==1)next }flag' > run0.201.plans.selected.xml
# Now transform key data into csv format:
# cat run0.201.plans.selected.xml | grep "person \|route \|leg " | sed 's/.*\<person id="//' | sed 's/"\>sfpt.*\<\/route\>//' | sed 's/" employed="yes"\>//' | sed 's/.*dep_time="//' | sed 's/" trav_time="/,/' | sed 's/"\>//' | sed 's/.*\<route type="links" start_link="//' | sed 's/" end_link="/,/' | sed 's/" distance="/,/' > run0.201.leg.data.csv

#do.or.load('/Users/critter/Documents/matsim/input/run0-201-plans-homes.Rdata',function(){
  #homes <- read.csv('/Users/critter/Documents/matsim/input/run0.201.plans.thinned.3.csv',header=F)

  #homes.id <- homes[is.na(homes$V2),1]
  #homes.loc <- homes[!is.na(homes$V2),]

  #homes <- data.table(id=homes.id,x=homes.loc$V1,y=homes.loc$V2)
  #return(list('homes'=homes))
#})
do.or.load('/Users/critter/Documents/matsim/input/run0-201-leg-data.Rdata',function(){
  legs <- data.table(read.csv('/Users/critter/Documents/matsim/input/run0.201.leg.data.csv',stringsAsFactors=F,header=F))
  legs[V2=='',V2:=NA]
  legs[V3=='',V3:=NA]
  legs[,num.na:=is.na(V2)+is.na(V3)+is.na(V4)]
  legs[,person:=ifelse(num.na==3,as.numeric(V1),NA)]
  legs[,dep.time:=ifelse(num.na==2,(V1),NA)]
  legs[,travel.time:=ifelse(num.na==2,(V2),NA)]
  legs[,start.link:=ifelse(num.na==0,(V1),NA)]
  legs[,end.link:=ifelse(num.na==0,(V2),NA)]
  legs[,travel.time.2:=ifelse(num.na==0,(V3),NA)]
  legs[,dist:=ifelse(num.na==0,as.numeric(V4),NA)]

  rep.person <- function(x){
    person.inds <- which(!is.na(x))
    num.to.fill <- diff(c(person.inds,length(x)+1))
    unlist(apply(cbind(x[person.inds],num.to.fill),1,function(xx){ rep(xx[1],xx[2]) }))
  }
  legs[,person:=rep.person(person)]
  legs[num.na<3, dep.time:=rep(dep.time[!is.na(dep.time)],each=2)]
  legs[num.na<3, travel.time:=rep(travel.time[!is.na(travel.time)],each=2)]
  legs <- legs[num.na==0,list(person,dep.time,travel.time,start.link,end.link,travel.time.2,dist)]
  legs[,miles:=dist/1609.34]
  legs[,dep.time.hr:=unlist(lapply(str_split(dep.time,":"),function(ll){ as.numeric(ll[1]) + as.numeric(ll[2])/60 }))]
  legs[,travel.time.hr:=unlist(lapply(str_split(travel.time,":"),function(ll){ as.numeric(ll[1]) + as.numeric(ll[2])/60 }))]
  legs[,arr.time.hr:=dep.time.hr+travel.time.hr]
  setkey(legs,person,dep.time.hr)
  legs[,dwell.time:=c(tail(dep.time.hr,-1)-head(arr.time.hr,-1),0),by='person']
  legs[,dwell.time:=ifelse(dwell.time<0,0,dwell.time)]
  legs[,range.replenished:=dwell.time * 5 /.35]
  track.range <- function(miles,replenish){
    tracked <- c(max(0,miles[1] - replenish[1]),rep(0,length(miles)-1))
    if(length(miles)>1){
      for(i in 2:length(miles)){
        tracked[i] <- max(0,tracked[i-1] + miles[i] - replenish[i])
      }
    }
    tracked
  }
  legs[,range.tracked:=track.range(miles,range.replenished),by='person']

  list(legs=legs)
})

do.or.load('/Users/critter/Documents/matsim/input/run0-201-plans-all.Rdata',function(){
  plans <- read.csv('/Users/critter/Documents/matsim/input/run0.201.plans.thinned4.csv',header=F)

  person.id <- as.numeric(as.character(plans$V1))
  plans$id <- unlist(alply(cbind(which(is.na(plans$V2)),c(tail(which(is.na(plans$V2)),-1)-1,nrow(plans))),.(1),function(ii){ rep(person.id[ii[1]],diff(ii)+1) }))
  plans.etc <- plans[!is.na(plans$V2),]

  plans <- data.table(id=plans.etc$id,type=plans.etc$V1,link.id=plans.etc$V2,x=plans.etc$V3,y=plans.etc$V4,end=plans.etc$V5)
  load(file=pp(matsim.shared,"model-inputs/development/network_SF_Bay_detailed.Rdata"))
  link.nodes <- join.on(links,nodes,'from','id',c('x','y'),'from.')
  link.nodes <- join.on(link.nodes,nodes,'to','id',c('x','y'),'to.')
  link.nodes[,link.x:=(to.x+from.x)/2]
  link.nodes[,link.y:=(to.y+from.y)/2]

  plans <- join.on(plans,link.nodes,'link.id','id',c('link.x','link.y'))
  setkey(plans,id,type)
  homes <- u(plans[type=='Home'])

  zip.of.homes <- over(SpatialPoints(homes[,list(link.x,link.y)],CRS("+init=epsg:26910")),sf.zips)$ZCTA5CE10
  homes[,zip:=as.numeric(as.character(zip.of.homes))]
  dists.by.person <- legs[,list(miles=sum(miles),max.trip=max(miles),min.range=max(range.tracked)),by='person']
  dists.by.person[,limiting.range:=ifelse(max.trip>min.range,max.trip,min.range)/.95]
  homes <- join.on(homes,dists.by.person,'id','person')

  return(list('plans'=plans,'homes'=homes))
})

do.or.load('/Users/critter/Documents/matsim/input/sf-bay-veh-reg.Rdata',function(){
  # Load and analyze the vehicle reg data
  reg <- read.csv('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/sf-bay-area-veh-reg/bay-area-detail-2015.csv')
  names(reg) <- c('zip','cat','segment','fuel','count')
  reg <- data.table(reg)
  reg.sum <- read.csv('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/sf-bay-area-veh-reg/bay-area-summary-2015.csv')
  names(reg.sum) <- c('cat','segment','fuel','make','model','year','frac')
  reg.sum <- data.table(reg.sum)
  reg <- join.on(reg,reg.sum,c('cat','segment','fuel'),c('cat','segment','fuel'),allow.cart=T)

  # deal with tiny zips (i.e. PO Boxes) that don't show up in SF Bay Model
  reg[zip==94018,zip:=94019] 
  reg[zip==94515,zip:=94574] 
  reg[zip==94957,zip:=94960] 
  reg[zip==95425,zip:=95448] 

  # break up segment
  reg[,seg.lux:=F]
  reg[grep('^LUXURY',reg$segment),seg.lux:=T]
  reg[,seg.lux:=ifelse(seg.lux,'LUXURY','NON-LUXURY')]
  reg[,seg.extra:=unlist(lapply(str_split(reg$segment,"LUXURY "),function(ll){ tail(ll,1)}))]
  reg[,seg.truck:=ifelse(seg.extra%in%c("COMMERCIAL TRUCK","FULL SIZE HALF TON PICKUP","FULL SIZE SUV","COMPACT PICKUP","FULL 3 QTR TO 1 TON VAN","FULL SIZE 3 QTR TO 1 TON PICKUP","FULL SIZE HALF TON VAN","MID SIZE PICKUP","MID SIZE VAN"),'TRUCK','NON-TRUCK')]
  reg[,make.model:=pp(make,' ',model)]
  reg[,zip:=as.numeric(as.character(zip))]
  reg[,id:=1:nrow(reg)]
  reg[,n:=count*frac]
  list('reg'=reg)
})

do.or.load('/Users/critter/Documents/matsim/input/sf-bay-sampled-plans.Rdata',function(){
  #setkey(reg,year,make)
  #ggplot(reg[,list(n=sum(n)),by=c('year','make')],aes(x=year,y=n,fill=make)) + 
    #geom_bar(stat='identity')
  #setkey(reg,year,cat)
  #ggplot(reg[,list(n=sum(n)),by=c('year','cat')],aes(x=year,y=n,fill=cat)) + 
    #geom_bar(stat='identity')
  #setkey(reg,year,segment)
  #ggplot(reg[,list(n=sum(n)),by=c('year','seg.lux','seg.extra','seg.truck')],aes(x=year,y=n,fill=seg.extra)) + 
    #geom_bar(stat='identity') + facet_wrap(seg.truck~seg.lux)

  #pev.pen <- reg[fuel%in%c('PHEV','EV'),list(n=sum(n)),by=c('year','make','make.model')]
  #setkey(pev.pen,year,make.model)
  #ggplot(pev.pen[n>50],aes(x=year,y=n,fill=make.model)) + geom_bar(stat='identity') 

  #ggplot(reg[fuel%in%c('PHEV','EV'),list(n=sum(n)),by=c('year','seg.lux','seg.extra')],aes(x=year,y=n,fill=seg.extra)) + 
    #geom_bar(stat='identity') + facet_wrap(~seg.lux)

  # Classify into BEV, PHEV, NEV so the sampling of plans can take this into account
  pev.pen <- reg[fuel%in%c('PHEV','EV') & zip%in%na.omit(u(homes$zip)) & !make.model%in%c('HYUNDAI SONATA','MINI COOPER','MCLAREN AUTOMOTIVE P1','FORD RANGER','TOYOTA RAV4','BMW ACTIVE E','MITSUBISHI I MIEV','INTERNATIONAL ESTAR','AZURE DYNAMICS TRANSIT CONNECT E','CHEVROLET S TRUCK','GM EV1 EV1','NISSAN ALTRA','HONDA EV PLUS'),list(n=sum(n)),by=c('make.model','zip')]
  pev.pen[,class:=ifelse(make.model %in% c('NISSAN LEAF','TESLA MODEL S','FIAT 500','BMW I3','VOLKSWAGEN E-GOLF','FORD FOCUS','CHEVROLET SPARK EV','TOYOTA RAV4 EV','MERCEDES-BENZ B','KIA SOUL EV','TESLA ROADSTER','HONDA FIT EV','BMW ACTIVE E','TOYOTA RAV4','TESLA MODEL X','FORD RANGER','GLOBAL ELECTRIC MOTORS E6','MINI COOPER'),'BEV',ifelse(make.model %in% c('GLOBAL ELECTRIC MOTORS 825','SMART FORTWO','FORD THINK NEIGHBOR','GLOBAL ELECTRIC MOTORS E4','GLOBAL ELECTRIC MOTORS EL','GLOBAL ELECTRIC MOTORS ES','GLOBAL ELECTRIC MOTORS E2','GLOBAL ELECTRIC MOTORS EL XD','GLOBAL ELECTRIC MOTORS E4 S','GLOBAL ELECTRIC MOTORS E6 S'),'NEV','PHEV'))]

  # For now, ignore NEVs
  pev.pen <- pev.pen[class!="NEV"]
  pev.pen[,id:=1:nrow(pev.pen)]
  veh.range <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/vehicles/vehicle-ranges.csv'))
  veh.types <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/vehicles/vehicle-types.csv'))
  pev.pen <- join.on(pev.pen,veh.range,'make.model','vehicleTypeName','epaRange')

  setkey(pev.pen,zip)
  setkey(homes,zip)

  pev.pen[,n.int:=ifelse(n>=1,round(n),sapply(n,function(lambda){ rpois(1,lambda)}))]
  pev.pen.orig <- copy(pev.pen)
  id.mapping <- pev.pen[,list(person.id.temp=1:n.int,zip=zip[1],class=class[1],range=epaRange[1]),by='id']
  bad.ids <- id.mapping[person.id.temp==0]$id
  id.mapping <- id.mapping[!id %in% bad.ids]
  id.mapping[,person.id.temp:=NULL]
  id.mapping[,smart.id:=as.numeric(NA)]
  setkey(id.mapping,zip,class)
  bad.zips <- c()
  homes[,used:=F]
  for(the.zip in u(pev.pen$zip)){
    n.in.zip <- nrow(id.mapping[zip==the.zip])
    the.bevs <- id.mapping[zip==the.zip & class=='BEV']
    n.consistent.with.bevs <- 0
    for(the.range in sort(u(the.bevs$range))){
      n.bevs <- nrow(the.bevs[range==the.range])
      n.consistent <- nrow(homes[zip==the.zip & limiting.range < the.range & used==F])
      n.consistent.with.bevs <- n.consistent.with.bevs + n.consistent
      if(n.consistent > n.bevs * 2){
        homes[zip==the.zip & limiting.range < the.range,used:=c(rep(T,n.bevs*2),rep(F,n.consistent - n.bevs*2))]
      }else{
        my.cat(pp('Warning: not enough sf bay peeps in zip: ',the.zip,' for BEVs with ',the.range,' range (',n.consistent,' vs ',n.bevs*2,')'))
        bad.zips <- c(bad.zips,the.zip)
        homes[zip==the.zip & limiting.range < the.range,used:=T]
      }
    }
    n.consistent.with.phevs <- nrow(homes[zip==the.zip & used==F])
    n.phevs <- nrow(id.mapping[zip==the.zip & class=='PHEV'])
    if(n.consistent.with.phevs < n.phevs * 2){
        my.cat(pp('Warning: not enough sf bay peeps in zip: ',the.zip,' for PHEVs (',n.consistent.with.phevs,' vs ',n.phevs*2,')'))
        bad.zips <- c(bad.zips,the.zip)
    }
  }
  bad.zips <- u(bad.zips)

  zip.homes <- zips[zips$ZCTA5CE10 %in% u(homes$zip),]
  zips.m <- data.table(coordinates(spTransform(zip.homes,CRS("+init=epsg:26910"))))
  zips.m[,':='(zip=as.numeric(as.character(zip.homes$ZCTA5CE10)),x=V1,y=V2,V1=NULL,V2=NULL)]
  bad.zips.m <- zips.m[zip %in% bad.zips]
  dist.mat <- dist2(bad.zips.m[,list(x,y)],zips.m[!zip %in% bad.zips,list(x,y)])
  closest.i <- apply(dist.mat,1,which.min)
  bad.zips.m[,closest.zip:=zips.m[!zip %in% bad.zips,list(zip)][closest.i]]
  # custome mappings
  bad.zips.m[zip==94105,closest.zip:=94107]

  pev.pen <- copy(pev.pen.orig)
  pev.pen[zip%in%bad.zips.m$zip,zip:=bad.zips.m$closest.zip[match(pev.pen[zip %in% bad.zips.m$zip]$zip,bad.zips.m$zip)]]
  setkey(pev.pen,zip)
  id.mapping <- pev.pen[,list(person.id.temp=1:n.int,zip=zip[1],class=class[1],range=epaRange[1]),by='id']
  bad.ids <- id.mapping[person.id.temp==0]$id
  id.mapping <- id.mapping[!id %in% bad.ids]
  id.mapping[,person.id.temp:=NULL]

  id.mapping[,smart.id.day1:=as.numeric(NA)]
  id.mapping[,smart.id.day2:=as.numeric(NA)]
  setkey(id.mapping,zip,class)
  homes[,used:=F]
  for(the.zip in u(id.mapping$zip)){
    the.bevs <- id.mapping[zip==the.zip & class=='BEV']
    for(the.range in sort(u(the.bevs$range))){
      n.bevs <- nrow(the.bevs[range==the.range])
      n.consistent <- nrow(homes[zip==the.zip & limiting.range < the.range & used==F])
      if(n.consistent >= n.bevs * 2){
        homes.to.use <- sample(homes[zip==the.zip & limiting.range < the.range & used==F]$id,n.bevs*2)
        homes[id%in%homes.to.use,used:=T]
        id.mapping[zip==the.zip & class=='BEV' & range==the.range,':='(smart.id.day1=homes.to.use[1:n.bevs],smart.id.day2=homes.to.use[(1+n.bevs):(2*n.bevs)])]
      }else{
        my.cat(pp('Warning: not enough sf bay peeps in zip: ',the.zip,' for BEVs with ',the.range,' range (',n.consistent,' vs ',n.bevs*2,')'))
        homes.to.use <- homes[zip==the.zip & limiting.range < the.range & used==F]$id
        homes[id%in%homes.to.use,used:=T]
        if(n.consistent>n.bevs){
          id.mapping[zip==the.zip & class=='BEV' & range==the.range,smart.id.day1:=homes.to.use[1:n.bevs]]
          id.mapping[zip==the.zip & class=='BEV' & range==the.range,smart.id.day2:=c(homes.to.use[(1+n.bevs):n.consistent],rep(NA,n.bevs*2-n.consistent))]
        }else{
          id.mapping[zip==the.zip & class=='BEV' & range==the.range,smart.id.day1:=c(homes.to.use[1:n.consistent],rep(NA,n.bevs-n.consistent))]
          id.mapping[zip==the.zip & class=='BEV' & range==the.range,smart.id.day2:=NA]
        }
      }
    }
    n.consistent.with.phevs <- nrow(homes[zip==the.zip & used==F])
    n.phevs <- nrow(id.mapping[zip==the.zip & class=='PHEV'])
    if(n.phevs>0){
      if(n.consistent.with.phevs < n.phevs*2){
          my.cat(pp('Warning: not enough sf bay peeps in zip: ',the.zip,' for PHEVs (',n.consistent.with.phevs,' vs ',n.phevs*2,')'))
          homes.to.use <- homes[zip==the.zip & used==F]$id
          homes[id%in%homes.to.use,used:=T]
          if(n.consistent.with.phevs>n.phevs){
            id.mapping[zip==the.zip & class=='PHEV',smart.id.day1:=homes.to.use[1:n.phevs]]
            id.mapping[zip==the.zip & class=='PHEV',smart.id.day2:=c(homes.to.use[(1+n.phevs):n.consistent.with.phevs],rep(NA,n.phevs*2-n.consistent.with.phevs))]
          }else{
            id.mapping[zip==the.zip & class=='PHEV',smart.id.day1:=c(homes.to.use[1:n.consistent.with.phevs],rep(NA,n.phevs-n.consistent.with.phevs))]
            id.mapping[zip==the.zip & class=='PHEV',smart.id.day2:=NA]
          }
      }else{
        homes.to.use <- sample(homes[zip==the.zip & used==F]$id,n.phevs*2)
        homes[id%in%homes.to.use,used:=T]
        id.mapping[zip==the.zip & class=='PHEV',smart.id.day1:=c(homes.to.use[1:n.phevs],smart.id.day2=homes.to.use[(1+n.phevs):(2*n.phevs)])]
      }
    }
  }
  sum(is.na(id.mapping$smart.id.day1))
  sum(is.na(id.mapping$smart.id.day2))
  id.mapping <- id.mapping[!is.na(smart.id)]
  sampled.reg <- join.on(id.mapping,pev.pen,'id','id','make.model')

  list('sampled.reg'=sampled.reg,'veh.types'=veh.types)
})

beam.pop <- join.on(sampled.reg,veh.types,'make.model','vehicleTypeName','id','vehicle.type')
beam.pop[,':='(homeChargingPlugTypeId=3,homeChargingPolicyId=5,homeChargingNetworkOperatorId=1)]
beam.pop[,':='(vehicleTypeId=vehicle.typeid,personId=smart.id,simulationStartSocFraction=rbeta(length(id),0.8,0.15))]

write.csv(beam.pop[,list(personId,vehicleTypeId,homeChargingPlugTypeId,homeChargingPolicyId,homeChargingNetworkOperatorId,simulationStartSocFraction)],'~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/person-attributes-from-reg.csv',row.names=F)

n.peeps <- 10000
tot.n <- nrow(beam.pop)

### NOTE, NEED TO CHANGE MODE IN FINAL PLANS FROM "car" to "PEV"
system("head -5 /Users/critter/Documents/matsim/input/beam-plans-from-cvrp.xml > /Users/critter/Documents/matsim/input/beam-plans-from-veh-reg.xml")
for(i in seq(1,tot.n,by=n.peeps)){
  peep.inds <- i:(min(i+n.peeps-1,tot.n))
  person.match <- pp(pp('person id="',beam.pop$smart.id[peep.inds],'"'),collapse='|')
  #cmd <- pp("awk '/",person.match,"/,/\\/person/' /Users/critter/Documents/matsim/input/run0.201.plans.selected.xml > /Users/critter/Documents/matsim/input/beam-pop-from-reg-part-",i,".xml")
  #system(cmd,wait=T)
  cmd <- pp("cat /Users/critter/Documents/matsim/input/beam-pop-from-reg-part-",i,".xml >> /Users/critter/Documents/matsim/input/beam-plans-from-veh-reg.xml")
  system(cmd,wait=T)
}
system("tail -4 /Users/critter/Documents/matsim/input/beam-plans-from-cvrp.xml >> /Users/critter/Documents/matsim/input/beam-plans-from-veh-reg.xml")

system("head -11000 /Users/critter/Documents/matsim/input/beam-plans-from-veh-reg.xml > /Users/critter/Documents/matsim/input/beam-plans-from-veh-reg-500.xml")
system("tail -4 /Users/critter/Documents/matsim/input/beam-plans-from-cvrp.xml >> /Users/critter/Documents/matsim/input/beam-plans-from-veh-reg-500.xml")

do.or.load('/Users/critter/Documents/matsim/input/sf-bay-id-from-cvrp.Rdata',function(){
  # Load and analyze the rebate data
  rebate <- data.table(read.csv('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/ca-clean-vehicle-rebates/cvrp.csv'))
  #rebate.in.sf <- rebate[ZIP %in% as.numeric(as.character(zips[zips.in.sf,]$ZCTA5CE10))]
  rebate.in.sf <- rebate[County %in%  c('Alameda','San Mateo','Sonoma','Marin','Contra Costa','Napa','Solano','Santa Clara','San Francisco')]
  rebate.in.sf[,dt:=to.posix(Application.Date,'%m/%d/%y')]
  rebate.in.sf[,':='(month=month(dt),year=year(dt))]

  #ggplot(rebate.in.sf[,list(n.rebates=length(Rebate.Dollars)),by=c('year','month','Vehicle.Make','County')],aes(x=year+(month-1)/12,y=n.rebates,fill=Vehicle.Make)) + 
    #geom_bar(stat='identity') + 
    #facet_wrap(~County)

  #ggplot(rebate.in.sf[,list(rebate.value=sum(Rebate.Dollars)),by=c('year','month','Vehicle.Category','County')],aes(x=year+(month-1)/12,y=rebate.value,fill=Vehicle.Category)) + 
    #geom_bar(stat='identity') + 
    #facet_wrap(~County)

  #ggplot(rebate.in.sf,aes(x=year+(month-1)/12,fill= Consumer.Type)) + geom_bar() 
  #ggplot(rebate.in.sf,aes(x=factor(year),fill=make))+geom_bar()+labs(x='Year',y="# Rebates",fill='Make')

  lump.into.other <- names(rev(sort(table(rebate.in.sf$Vehicle.Make))))[which(names(rev(sort(table(rebate.in.sf$Vehicle.Make)))) == 'GEM'):length(u(rebate.in.sf$Vehicle.Make))]
  rebate.in.sf[,Vehicle.Make:=as.character(Vehicle.Make)]
  rebate.in.sf[,make:=ifelse(Vehicle.Make%in%lump.into.other,'Other',Vehicle.Make)]
  rebate.in.sf[,zip:=as.numeric(as.character(ZIP))]

  # deal with tiny zips (i.e. PO Boxes) that don't show up in SF Bay Model
  rebate.in.sf[zip==94011,zip:=94010] 
  rebate.in.sf[zip==94018,zip:=94019] 
  rebate.in.sf[zip==94023,zip:=94022] 
  rebate.in.sf[zip==94026,zip:=94025] 
  rebate.in.sf[zip==94042,zip:=94043] 
  rebate.in.sf[zip==94088,zip:=94089] 
  rebate.in.sf[zip==94126,zip:=94111] 
  rebate.in.sf[zip==94140,zip:=94110] 
  rebate.in.sf[zip==94142,zip:=94102] 
  rebate.in.sf[zip==94302,zip:=94301] 
  rebate.in.sf[zip==94957,zip:=94960] 
  rebate.in.sf[zip==95425,zip:=95448] 
  rebate.in.sf[zip==94515,zip:=94574] 
  setkey(rebate.in.sf,zip)
  setkey(homes,zip)

  rebate.in.sf[,smart.bay.id:=as.numeric(NA)]
  for(the.zip in u(rebate.in.sf$zip)){
    if(nrow(rebate.in.sf[J(the.zip)]) > 5){
      if(nrow(homes[J(the.zip)]) < nrow(rebate.in.sf[J(the.zip)])){
        my.cat(pp('Warning: not enough sf bay peeps in zip: ',the.zip,' (',nrow(homes[J(the.zip)]),' vs ',nrow(rebate.in.sf[J(the.zip)]),')'))
        rebate.in.sf[J(the.zip),smart.bay.id:=c(homes[J(the.zip)]$id,rep(NA,length(ID)-nrow(homes[J(the.zip)])))]
      }else{
        rebate.in.sf[J(the.zip),smart.bay.id:=sample(homes[J(the.zip)]$id,length(ID))]
      }
    }
  }

  sum(is.na(rebate.in.sf$smart.bay.id))
  list('rebate.in.sf'=rebate.in.sf)
})


# use awk n people at a time to extract the plans
n.peeps <- 10000
beam.pop <- rebate.in.sf[!is.na(smart.bay.id)]
tot.n <- nrow(beam.pop)


for(i in seq(1,tot.n,by=n.peeps)){
  peep.inds <- i:(min(i+n.peeps-1,tot.n))
  person.match <- pp(pp('person id="',beam.pop$smart.bay.id[peep.inds],'"'),collapse='|')
  cmd <- pp("awk '/",person.match,"/,/\\/person/' /Users/critter/Documents/matsim/input/run0.201.plans.xml > /Users/critter/Documents/matsim/input/beam-pop-part-",i,".xml")
  system(cmd,wait=T)
}

# Finally make the vehicle type mapping
vehs <- data.table(read.csv('~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/vehicle-types.csv'))

beam.pop <- join.on(beam.pop,vehs,'make','vehicleTypeName','id','vehicle.type')

beam.pop[,':='(homeChargingPlugTypeId=3,homeChargingPolicyId=5,homeChargingNetworkOperatorId=1)]
beam.pop[,':='(vehicleTypeId=vehicle.typeid,personId=smart.bay.id)]

write.csv(beam.pop[,list(personId,vehicleTypeId,homeChargingPlugTypeId,homeChargingPolicyId,homeChargingNetworkOperatorId)],'~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/person-attributes.csv',row.names=F)


# Some analysis of the plans
plans[,act.end:=to.posix(end,'%H:%M:%S')]
plans[,act.end.hr:=hour(act.end)]
plans[,type:=factor(type)]
plans.summ <- na.omit(plans[,list(n=length(end)),by=c('act.end.hr','type')])
setkey(plans.summ,type,act.end.hr)
ggplot(plans.summ,aes(x=act.end.hr,y=n,fill=type))+geom_bar(position='stack',stat='identity')+labs(x="Hour",y="# Departures",fill="Depart from:")

plans[is.na(act.end),act.end:=max(plans$act.end,na.rm=T)+3600]
setkey(plans,id,act.end)
plans[,arr.to:=c(tail(type,-1),NA),by=c('id')]
plans[,arr.to:=factor(levels(plans$type)[arr.to])]

plans.summ <- plans[,list(n=length(end)),by=c('act.end.hr','arr.to')]
setkey(plans.summ,arr.to,act.end.hr)
ggplot(plans.summ,aes(x=act.end.hr,y=n,fill=arr.to))+geom_bar(position='stack',stat='identity')+labs(x="Hour",y="# Departures",fill="Depart to:")


# Analysis of legs, distances, difference between planned departures and actual
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

hist(as.numeric(legs$dep.delay[abs(legs$dep.delay)<10000])/3600,breaks=100,xlab="Hours",main="Difference Between Planned and Relaxed Departure Time")
hist(legs[,list(dist=sum(dist)/1609),by='id']$dist,breaks=100,xlab="Miles",main="Daily Travel Distances")
hist(legs$dist/1609,breaks=100,xlab="Miles",main="Trip Travel Distances")

