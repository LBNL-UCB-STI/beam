load.libraries(c('XML'))

do.or.load('/Users/critter/Documents/beam/input/run0-201-leg-data.Rdata',function(){
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
do.or.load('/Users/critter/Documents/beam/input/run0-201-plans-all.Rdata',function(){
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

# Group into cases to make it easy to deal with the carry-over from last plan to the next
#
# 98% of plans being and end at Home
# sum(plans[,head(type,1)=='Home' && tail(type,1)=='Home',by='id']$V1)

home.to.home <- u(plans[,head(type,1)=='Home' & tail(type,1)=='Home',by='id'][V1==T]$id)
other.types <- u(plans[,head(type,1)=='Home' & tail(type,1)=='Home',by='id'][V1==F]$id)

new.plans <- list()
for(person in home.to.home){
  n.acts <- nrow(plans[id==person])
  plans[id==person,end.dt:=c(head(end.dt,-1),end.dt[1]+24*3600)]
  new.plans[[length(new.plans)+1]] <- rbindlist(list(plans[id==person,list(type=type,link=link.id,id=id,x=x,y=y,end_time=end.dt)],
                                                     plans[id==person][2:n.acts,list(type=type,link=link.id,id=id,x=x,y=y,end_time=end.dt+24*3600)],
                                                     plans[id==person][2:n.acts,list(type=type,link=link.id,id=id,x=x,y=y,end_time=end.dt+48*3600)]))
}
for(person in other.types){
  n.acts <- nrow(plans[id==person])
  plans[id==person,end.dt:=c(head(end.dt,-1),to.posix('1970-01-02'))]
  new.plans[[length(new.plans)+1]] <- rbindlist(list(plans[id==person,list(type=type,link=link.id,id=id,x=x,y=y,end_time=end.dt)],
                                                     plans[id==person][,list(type=type,link=link.id,id=id,x=x,y=y,end_time=end.dt+24*3600)],
                                                     plans[id==person][,list(type=type,link=link.id,id=id,x=x,y=y,end_time=end.dt+48*3600)]))
}
new.plans <- rbindlist(new.plans)
new.plans[,link:=pp('sfpt',link)]
new.legs <- new.plans[,list(start_link=head(link,-1),end_link=tail(link,-1),trav_time=1,distance=1),by='id']
save(new.plans,new.legs,file='/Users/critter/Documents/beam/input/sf-bay-sampled-plans-multi-day.Rdata')
load(file='/Users/critter/Documents/beam/input/sf-bay-sampled-plans-multi-day.Rdata')

outfile <- '/Users/critter/Documents/beam/input/sf-bay-sampled-plans-multi-day.xml'
outfile.500 <- '/Users/critter/Documents/beam/input/sf-bay-sampled-plans-multi-day-500.xml'

the.str <- '<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE population SYSTEM "http://www.matsim.org/files/dtd/population_v5.dtd">\n\n<population>\n'
cat(the.str,file=outfile,append=F)
cat(the.str,file=outfile.500,append=F)
i <- 1
for(person in u(new.plans$id)){
  the.str <- pp('\t<person id="',person,'" employed="yes">\n\t\t<plan selected="yes">\n')
  the.hr <- as.numeric(strftime(new.plans[id==person]$end_time,'%H')) + 24*(as.numeric(strftime(new.plans[id==person]$end_time,'%j'))-1)
  the.min <- as.numeric(strftime(new.plans[id==person]$end_time,'%M'))
  the.acts <- pp('\t\t\t<act end_time="',the.hr,':',formatC(the.min,width = 2, format = "d", flag = "0"),':00" link="',new.plans[id==person]$link,'" type="',new.plans[id==person]$type,'" x="',new.plans[id==person]$x,'" y="',new.plans[id==person]$y,'"/>')
  the.legs <- pp('\t\t\t<leg mode="PEV"><route type="links" start_link="',new.legs[id==person]$start_link,'" end_link="',new.legs[id==person]$end_link,'" trav_time="',new.legs[id==person]$trav_time,'" distance="',new.legs[id==person]$distance,'">',new.legs[id==person]$start_link,' ',new.legs[id==person]$end_link,'</route></leg>')
  the.str <- pp(the.str,pp(rbind(the.acts,c(the.legs,'')),collapse='\n'),'\t\t</plan>\n\t</person>\n')
  cat(the.str,file=outfile,append=T)
  if(i <= 500){
    cat(the.str,file=outfile.500,append=T)
    i <- i + 1
  }
}
the.str <- '\n<!-- ====================================================================== -->\n\n</population>'
cat(the.str,file=outfile,append=T)
cat(the.str,file=outfile.500,append=T)

