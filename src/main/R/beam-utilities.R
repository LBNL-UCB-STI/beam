
load.libraries(c('GEOquery','XML'))

clean.and.relabel <- function(ev,factor.to.scale.personal.back,factor.to.scale.transit.back,val.of.time=16.9){
  # Clean and relabel
  ev[vehicle_type=="bus",vehicle_type:="BUS-DEFAULT"]
  ev[vehicle_type=="CAR",vehicle_type:="Car"]
  ev[substr(vehicle,1,5)=="rideH",mode:="ride_hail"]
  ev[vehicle_type=="subway",vehicle_type:="SUBWAY-DEFAULT"]
  ev[vehicle_type=="SUV",vehicle_type:="Car"]
  ev[vehicle_type=="cable_car",vehicle_type:="CABLE_CAR-DEFAULT"]
  ev[vehicle_type=="tram",vehicle_type:="TRAM-DEFAULT"]
  ev[vehicle_type=="rail",vehicle_type:="RAIL-DEFAULT"]
  ev[vehicle_type=="ferry",vehicle_type:="FERRY-DEFAULT"]
  transit.types <- c('BUS-DEFAULT','FERRY-DEFAULT','TRAM-DEFAULT','RAIL-DEFAULT','CABLE_CAR-DEFAULT','SUBWAY-DEFAULT')
  ev[,tripmode:=ifelse(mode%in%c('subway','bus','rail','tram','walk_transit','drive_transit','cable_car','ferry'),'transit',as.character(mode))]
  ev[,hour:=time/3600]
  ev[,hr:=round(hour)]
  setkey(ev,vehicle_type)
  if(is.factor(ev$start.x[1]))ev[,start.x:=as.numeric(as.character(start.x))]
  if(is.factor(ev$end.x[1]))ev[,end.x:=as.numeric(as.character(end.x))]
  if(is.factor(ev$start.y[1]))ev[,start.y:=as.numeric(as.character(start.y))]
  if(is.factor(ev$end.y[1]))ev[,end.y:=as.numeric(as.character(end.y))]
  if(is.factor(ev$num_passengers[1]))ev[,num_passengers:=as.numeric(as.character(num_passengers))]
  if(is.factor(ev$capacity[1]))ev[,capacity:=as.numeric(as.character(capacity))]
  if(is.factor(ev$expectedMaximumUtility[1]))ev[,expectedMaximumUtility:=as.numeric(as.character(expectedMaximumUtility))]
  if(is.factor(ev$fuel[1]))ev[,fuel:=as.numeric(as.character(fuel))]
  ev[start.y<=0.003 | end.y <=0.003,':='(start.x=NA,start.y=NA,end.x=NA,end.y=NA)]
  ev[length==Inf,length:=NA]
  ev[vehicle_type%in%transit.types & !is.na(start.x)  & !is.na(start.y)  & !is.na(end.y)  & !is.na(end.y),length:=dist.from.latlon(start.y,start.x,end.y,end.x)]
  ev[vehicle_type%in%transit.types,num_passengers:=round(num_passengers*factor.to.scale.personal.back)]
  ev[vehicle_type%in%transit.types,capacity:=round(capacity*factor.to.scale.transit.back)]
  ev[num_passengers > capacity,num_passengers:=capacity]
  ev[,pmt:=num_passengers*length/1609]
  ev[is.na(pmt),pmt:=0]
  #ev[,expectedMaximumUtility:=expectedMaximumUtility-quantile(ev$expectedMaximumUtility,probs=.001,na.rm=T)]
  #ev[,expectedMaximumUtility:=expectedMaximumUtility-mean(ev$expectedMaximumUtility,na.rm=T)]
  ev[,numAlternatives:=0]
  ev[expectedMaximumUtility==-Inf,expectedMaximumUtility:=NA]
  ev[type=='ModeChoice',numAlternatives:=str_count(availableAlternatives,":")+1]
  ev[type=='ModeChoice',carSurplus:=log(exp(-length/1609/45*val.of.time))]
  ev[type=='ModeChoice',access:=expectedMaximumUtility-carSurplus]
  ev
}

pretty.titles <- c('Ride Hail Number'='ridehail_num',
                   'Ride Hail Price'='ridehail_price',
                   'Transit Capacity'='transit_capacity',
                   'Transit Price'='transit_price',
                   'Toll Price'='toll_price',
                   'Value of Time'='vot_vot',
                   'Value of Time'='valueOfTime'
                   )
to.title <- function(abbrev){ 
  if(abbrev %in% pretty.titles){
    names(pretty.titles[which(pretty.titles==abbrev)]) 
  }else{
    abbrev
  }
}
pretty.modes <- function(ugly){
  pretty.list <- c('Ride Hail'='ride_hail',
                   'Ride Hail - Transit'='ride_hail_transit',
                   'Cable Car'='cable_car',
                   'Car'='car',
                   'Walk'='walk',
                   'Tram'='tram',
                   'Transit'='transit'
                   )
  sapply(ugly,function(the.ugly){
    if(the.ugly %in% pretty.list){
      names(pretty.list[which(pretty.list==the.ugly)]) 
    }else{
      the.ugly
    }
  })
}

parse.link.stats <- function(link.stats.file,net.file=NA){
  file.rdata <- pp(link.stats.file,'.Rdata')
  if(file.exists(file.rdata)){
    load(file.rdata)
  }else{
    stats <- data.table(read.csv(link.stats.file,fill=T))
    stats[,hour:=as.numeric(as.character(hour))]
    setkey(stats,link,stat,hour)
    stats <- unique(stats)
    save(stats,file=file.rdata)
  }
  stats
}

my.colors <- c(blue='#377eb8',green='#227222',orange='#C66200',purple='#470467',red='#B30C0C',yellow='#C6A600',light.green='#C0E0C0',magenta='#D0339D',dark.blue='#23128F',brown='#542D06',grey='#8A8A8A',dark.grey='#2D2D2D',light.yellow='#FFE664',light.purple='#9C50C0',light.orange='#FFB164',black='#000000')
mode.colors <- c('Ride Hail'='red',Car='grey',Walk='green',Transit='blue','Ride Hail - Transit'='purple')
mode.colors <- data.frame(key=names(mode.colors),color=mode.colors,color.hex=my.colors[mode.colors])

download.from.nersc <- function(experiment.dir,include.pattern='*'){
  cmd <- pp("rsync -rav -e ssh --include '*/' --include='",include.pattern,"' --exclude='*' csheppar@cori.nersc.gov:/global/cscratch1/sd/csheppar/",experiment.dir," /Users/critter/Documents/matsim/pev/")
  system(cmd)
}

# Useful for managing large objects
list_obj_sizes <- function(list_obj=ls(envir=.GlobalEnv)){ 
  sizes <- sapply(list_obj, function(n) object.size(get(n)), simplify = FALSE) 
  print(sapply(sizes[order(-as.numeric(sizes))], function(s) format(s, unit = 'auto'))) 
}
# coord.names must end in x/y 
xy.dt.to.latlon <- function(dt,coord.names=c('x','y')){
  xy <- data.frame(x=streval(pp('dt$',coord.names[1])),y=streval(pp('dt$',coord.names[2])))
  xy <- SpatialPoints(xy,proj4string=CRS("+init=epsg:26910"))
  xy <- data.frame(coordinates(spTransform(xy,CRS("+init=epsg:4326"))))
  prefix <- substr(coord.names,0,nchar(coord.names)-1)
  streval(pp('dt[,',prefix[1],'lon:=xy$x]'))
  streval(pp('dt[,',prefix[2],'lat:=xy$y]'))
  dt
}
xy.to.latlon <- function(str,print=T){
  if(length(grep("\\[",str))>0){
    tmp <- strsplit(strsplit(str,'\\[x=')[[1]][2],'\\]\\[y=')[[1]]
    x <- as.numeric(tmp[1])
    y <- as.numeric(strsplit(tmp[2],'\\]')[[1]][1])
  }else if(length(grep('"',str))>0){
    x <- as.numeric(strsplit(str,'"')[[1]][2])
    y <- as.numeric(strsplit(str,'"')[[1]][4])
  }else if(length(grep(',',str))>0){
    x <- as.numeric(strsplit(str,',')[[1]][1])
    y <- as.numeric(strsplit(str,',')[[1]][2])
  }else if(length(grep(' ',str))>0){
    x <- as.numeric(strsplit(str,' ')[[1]][1])
    y <- as.numeric(strsplit(str,' ')[[1]][2])
  }else{
    return('Parse Error')
  }
  xy <- data.frame(x=x,y=y)
  xy <- SpatialPoints(xy,proj4string=CRS("+init=epsg:26910"))
  xy <- data.frame(coordinates(spTransform(xy,CRS("+init=epsg:4326"))))
  if(print){
    my.cat(pp(xy$y,',',xy$x))
  }else{
    return(pp(xy$y,',',xy$x))
  }
}
dist.from.latlon <- function(lat1,lon1,lat2,lon2){
  xy1 <- data.frame(x=lon1,y=lat1)
  xy1 <- SpatialPoints(xy1,proj4string=CRS("+init=epsg:4326"))
  xy1 <- data.frame(coordinates(spTransform(xy1,CRS("+init=epsg:26910"))))
  xy2 <- data.frame(x=lon2,y=lat2)
  xy2 <- SpatialPoints(xy2,proj4string=CRS("+init=epsg:4326"))
  xy2 <- data.frame(coordinates(spTransform(xy2,CRS("+init=epsg:26910"))))
  sqrt((xy1$x-xy2$x)^2 + (xy1$y-xy2$y)^2)
}

strtail <- function(s,n=1) {
   if(n<0) 
     substring(s,1-n) 
   else 
     substring(s,nchar(s)-n+1)
 }
strhead <- function(s,n) {
   if(n<0) 
     substr(s,1,nchar(s)+n) 
   else 
     substr(s,1,n)
}

csv2rdata <- function(csv.file){
  rdata.file <- pp(head(str_split(csv.file,'csv')[[1]],-1),'Rdata')
  if(!file.exists(rdata.file)){
    if(!file.exists(csv.file) & grepl("\\.gz$",csv.file))csv.file <- str_split(csv.file,".gz")[[1]][1]
    if(!file.exists(csv.file) & grepl("\\.csv$",csv.file))csv.file <- pp(csv.file,".gz")
    if(file.exists(csv.file)){
      headers <- unlist(as.vector(read.table(csv.file,header=F,nrows=1,sep=',',stringsAsFactors=F)))
      firstrow <- as.vector(read.table(csv.file,header=F,skip=1,nrows=1,sep=','))
      if(length(headers)<length(firstrow)){
        headers <- c(unlist(headers),pp("V",1:(length(firstrow)-length(headers))))
      }
      df <- data.table(read.csv(csv.file,fill=T,col.names=headers))
      save(df,file=rdata.file)
    }else{
      my.cat(pp("File not found: ",csv.file))
      df <- data.table(dat=NA)
    }
  }else{
    load(rdata.file)
  }
  return(df)
}

# Not ready yet
#plans2rdata <- function(plans.file){
  #rdata.file <- pp(head(str_split(plans.file,'xml')[[1]],-1),'Rdata')
  #if(!file.exists(rdata.file)){
    #if(file.exists(plans.file)){
      #tmpdir <- tempdir()
      #tmpfile <- pp(tmpdir,'/plans.xml')
      #gunzip(plans.file, destname = tmpfile, remove=F)

      #xmlToList(tmpfile)

      #doc <- xmlTreeParse(tmpfile, useInternalNodes = TRUE)
      #xpathApply(doc, "//population//person", function(x) do.call(paste, as.list(xmlValue(x))))
      #xpathSApply(doc, "//book", function(x) strsplit(xmlValue(x), " "))
      #xpathSApply(doc, "//book/child::*", xmlValue)

      #df <- data.table(read.csv(csv.file))
      #save(df,file=rdata.file)
    #}else{
      #my.cat(pp("File not found: ",csv.file))
      #df <- data.table(dat=NA)
    #}
  #}else{
    #load(rdata.file)
  #}
  #return(df)
#}

repeat_last = function(x, forward = TRUE, maxgap = Inf, na.rm = FALSE) {
    if (!forward) x = rev(x)           # reverse x twice if carrying backward
    ind = which(!is.na(x))             # get positions of nonmissing values
    if (is.na(x[1]) && !na.rm)         # if it begins with NA
        ind = c(1,ind)                 # add first pos
    rep_times = diff(                  # diffing the indices + length yields how often
        c(ind, length(x) + 1) )          # they need to be repeated
    if (maxgap < Inf) {
        exceed = rep_times - 1 > maxgap  # exceeding maxgap
        if (any(exceed)) {               # any exceed?
            ind = sort(c(ind[exceed] + 1, ind))      # add NA in gaps
            rep_times = diff(c(ind, length(x) + 1) ) # diff again
        }
    }
    x = rep(x[ind], times = rep_times) # repeat the values at these indices
    if (!forward) x = rev(x)           # second reversion
    x
}

dir.slash <- function(the.dirs){
  sapply(the.dirs,function(the.dir){ ifelse(strtail(the.dir)=="/",the.dir,pp(the.dir,"/")) })
}

read.data.table.with.filter <- function(filepath,match.words,header.word=NA){
  if(!is.na(header.word))match.words <- c(match.words,header.word)
  match.string <- pp("'",pp(match.words,collapse="\\|"),"'")
  return(data.table(read.csv.sql(filepath,filter=pp("grep ",match.string))))
}
substrRight <- function(x, n){
  substr(x, nchar(x)-n+1, nchar(x))
}
