
do.or.load('/Users/critter/Dropbox/ucb/vto/viz/base-layers/base-layers.Rdata',function(){
  map <- readOGR('/Users/critter/Dropbox/ucb/vto/viz/base-layers/','sf-bay-area-water-all')
  suppressWarnings(proj4string(map) <- CRS("+init=epsg:3857"))
  map.low <- readOGR('/Users/critter/Dropbox/ucb/vto/viz/base-layers/','sf-bay-area-water-all-low-zoom')
  suppressWarnings(proj4string(map.low) <- CRS("+init=epsg:3857"))
  buildings <- data.table()
  #buildings <- readOGR('/Users/critter/Dropbox/ucb/vto/viz/base-layers/','sf-bay-area-buildings')
  #suppressWarnings(proj4string(buildings) <- CRS("+init=epsg:3857"))
  list(map=map,map.low=map.low,buildings=buildings)
})

do.or.load('/Users/critter/Documents/matsim/viz/sites.Rdata',function(){
  zoom.scales <- data.frame(matrix(c(25,35.2655381269135,24,70.5310762538964,23,141.062152507932,22,282.124305016141,21,564.248610032838,20,1128.497220,19,2256.994440,18,4513.988880,17,9027.977761,16,18055.955520,15,36111.911040,14,72223.822090,13,144447.644200,12,288895.288400,11,577790.576700,10,1155581.153000,9,2311162.307000,8,4622324.614000,7,9244649.227000,6,18489298.450000,5,36978596.910000,4,73957193.820000,3,147914387.600000,2,295828775.300000,1,591657550.500000),ncol=2,byrow=T))
  names(zoom.scales) <- c('level','scale')
  zoom.scales <- data.table(zoom.scales,key='level')
  sites <- read.csv(pp(matsim.shared,"model-inputs/development/charging-sites-1k.csv"))
  coordinates(sites) <- c("longitude", "latitude")
  proj4string(sites) <- CRS("+init=epsg:4326") 
  sites.trans <- coordinates(spTransform(sites,CRS("+init=epsg:3857")))
  sites$x <- sites.trans[,'longitude']
  sites$y <- sites.trans[,'latitude']
  sites <- as.data.table(sites)
  list(sites=sites,zoom.scales=zoom.scales)
})

do.or.load('/Users/critter/Documents/matsim/viz/network.Rdata',function(){
  load(file=pp(matsim.shared,"model-inputs/development/network_SF_Bay_detailed.Rdata"))
  node.ids <- nodes$id
  coordinates(nodes) <- c('x','y')
  proj4string(nodes) <- CRS("+init=epsg:26910") 
  nodes <- data.table(coordinates(spTransform(nodes,CRS("+init=epsg:3857"))))
  nodes[,id:=node.ids]
  link.nodes <- join.on(links,nodes,'from','id',c('x','y'),'from.')
  link.nodes <- join.on(link.nodes,nodes,'to','id',c('x','y'),'to.')
  link.nodes[freespeed<13,type:='1-local']
  link.nodes[freespeed>=13 & freespeed<20,type:='2-collector']
  link.nodes[freespeed>=20,type:='3-arterial']
  setkey(link.nodes,type)
  link.nodes[J('1-local'),':='(col='#454545',lwd=1)]
  link.nodes[J('2-collector'),':='(col='#7e7e7e',lwd=1.5)]
  link.nodes[J('3-arterial'),':='(col='#adadad',lwd=2)]
  list(link.nodes=link.nodes)
})

do.or.load('/Users/critter/Documents/matsim/viz/agent-data.Rdata',function(){
  ###############################################
  ##### Load the events
  ###############################################
  ev <- data.table(read.csv('/Users/critter/Documents/matsim/pev/development_2016-07-31_14-06-53/ITERS/it.0/run0.0.events.csv',stringsAsFactors=F))

  ###############################################
  ##### Pre-process the mobility data 
  ###############################################
  agent.mob <- ev[type%in%c('DepartureChargingDecision','ArrivalChargingDecision') & choice %in% c(NA,'depart','enRoute','charge','park')]
  agent.mob[,pair:=rep(1:(length(time)/2),each=2),by='person']
  # for now, ignore pairs that aren't length 2 (due to bugs in BEAM)
  bad.peeps <- agent.mob[,length(time),by=c('person','pair')][V1!=2]$person
  agent.mob.m <- agent.mob[!person%in%bad.peeps,list(start=time[1],end=time[2],t=seq(time[1],time[2],by=1),duration=diff(time),route=route[1],soc=seq(soc[1],soc[2],length.out=diff(time)+1)),by=c('person','pair')]
  agent.mob.m <- agent.mob.m[duration>60]
  agent.mob.m[soc==1,soc:=0.9999]
  t <- agent.mob.m$t[1]
  start <- agent.mob.m$start[1] 
  end <- agent.mob.m$end[1]
  route <- agent.mob.m$route[1]
  t <- agent.mob.m$t[1:10]
  start <- agent.mob.m$start[1:10] 
  end <- agent.mob.m$end[1:10]
  route <- agent.mob.m$route[1:10]
  setkey(link.nodes,id)
  agent.mob.m[,c('x','y'):=interp.loc.fast(t,start[1],end[1],route[1]),by=c('person','pair')]
  agent.mob.m[,dir:=calc.direction(x,y),by=c('person','pair')]
  agent.mob.m[,alpha:=1]

  ###############################################
  ##### Pre-process the charging data 
  ###############################################
  ch.events <- ev[person%in%u(agent.mob.m$person) & type%in%c('PreCharge','departure')]
  ch.sessions <- ev[person%in%u(agent.mob.m$person) & type%in%c('BeginChargingSession','EndChargingSession')]

  ch.events[,pair:=c(0,rep(1:((length(time)-1)/2),each=2)),by='person']
  ch.events[,pair.n:=length(time),by=c('person','pair')]
  ch.events <- ch.events[pair>0 & pair.n==2,list(start=time[1],end=time[2],t=seq(time[1],time[2],by=1),duration=diff(time),soc=soc[1],plugType=plugType[1],site=site[1],plug=plug[1]),by=c('person','pair')]
  ch.sessions <- ch.sessions[,pair:=rep(1:((length(time)-1)/2),each=2),by='person']
  ch.sessions[,pair.n:=length(time),by=c('person','pair')]
  ch.sessions <- ch.sessions[pair.n==2,list(start=time[1],end=time[2],t=seq(time[1],time[2],by=1),duration=diff(time),soc=seq(soc[1],soc[2],length.out=diff(time)+1),charging=T),by=c('person','pair')]

  ch.events <- join.on(ch.events,ch.sessions,c('person','t'),c('person','t'),c('soc','charging'),'session.')
  ch.events[is.na(session.charging),session.charging:=F]
  ch.events[,':='(is.charging=session.charging,session.charging=NULL)]

  setkey(ch.events,person)
  heal.soc <- function(soc,the.t,the.person){
    if(length(soc)>0 && !is.na(soc[1])){
      beg.na <- which(is.na(soc))[c(1,which(diff(which(is.na(soc)))!=1)+1)] 
      end.na <- c(which(is.na(soc))[which(diff(which(is.na(soc)))!=1)],tail(which(is.na(soc)),1))
      if(length(beg.na)>0){
        if(!is.na(beg.na[1])){
          if(beg.na[1]==1){
            beg.na<-tail(beg.na,-1)
            end.na<-tail(end.na,-1)
          }
          if(length(beg.na)>0){
            for(i in 1:length(beg.na)){
              soc[beg.na[i]:end.na[i]] <- soc[beg.na[i]-1]
            }
          }
        }
      }
    }
    if(any(is.na(soc))){
      beg.na <- which(is.na(soc))[c(1,which(diff(which(is.na(soc)))!=1)+1)]
      end.na <- c(which(is.na(soc))[which(diff(which(is.na(soc)))!=1)],tail(which(is.na(soc)),1))
      if(beg.na[1]==1 && end.na[1] < length(soc)){
        soc[1:end.na[1]] <- soc[end.na[1]+1]
      }
      if(tail(end.na,1)==length(soc) && tail(beg.na,1) > 1){
        soc[tail(beg.na,1):tail(end.na,1)] <- soc[tail(beg.na,1)-1]
      }
    }
    if(all(is.na(soc))){
      beg.event <- c(1,which(diff(the.t)!=1)+1)
      end.event <- c(which(diff(the.t)!=1),length(soc))
      for(i in 1:length(beg.event)){
        soc[beg.event[i]:end.event[i]] <- agent.mob.m[J(the.person,the.t[beg.event[i]]),list(soc)]$soc
      }
    }
    soc
  }
  setkey(agent.mob.m,person,t)
  ch.events[is.na(soc),soc:=session.soc]
  peeps.to.heal <- u(ch.events[is.na(soc)]$person)
  healed.soc <- ch.events[person %in% peeps.to.heal,list(session.soc.healed=heal.soc(session.soc,t,person[1])),by='person']
  ch.events[person %in% peeps.to.heal,session.soc.healed:=healed.soc$session.soc.healed]
  ch.events[is.na(soc),soc:=session.soc.healed]
  ch.events[,':='(session.soc.healed=NULL,session.soc=NULL)]
  peeps.to.heal <- u(ch.events[is.na(soc)]$person)
  ch.events <- ch.events[!person%in%peeps.to.heal]

  ch.events <- join.on(ch.events,sites,c('site'),c('id'),c('x','y'),'site.')
  ch.events[,rank:=order(start),by=c('t','plug')] 
  setkey(ch.events,site,plug)
  plugs <- unique(ch.events[,list(site,plug)])
  plugs[,plug.i:=1:length(plug),by='site']
  ch.events <- join.on(ch.events,plugs,c('site','plug'),c('site','plug'),c('plug.i'),'')

  ch.events[,alpha:=1]
  ch.events[,':='(session.soc.healed=NULL,session.soc=NULL,start=NULL,end=NULL)]
  agent.mob.m[,route:=NULL]
  list(ch.events=ch.events,agent.mob.m=agent.mob.m)
})

