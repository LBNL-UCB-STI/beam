
load.libraries(c('GEOquery','XML'))

clean.and.relabel <- function(ev,factor.to.scale.personal.back,factor.to.scale.transit.back,val.of.time=16.9){
  for(in.use in names(ev)){
    # Clean and relabel
    ev[[in.use]][vehicleType=="bus",vehicleType:="BUS-DEFAULT"]
    ev[[in.use]][vehicleType=="CAR",vehicleType:="Car"]
    ev[[in.use]][substr(vehicle,1,5)=="rideH",mode:="ride_hail"]
    ev[[in.use]][vehicleType=="subway",vehicleType:="SUBWAY-DEFAULT"]
    ev[[in.use]][vehicleType=="SUV",vehicleType:="Car"]
    ev[[in.use]][vehicleType=="cable_car",vehicleType:="CABLE_CAR-DEFAULT"]
    ev[[in.use]][vehicleType=="tram",vehicleType:="TRAM-DEFAULT"]
    ev[[in.use]][vehicleType=="rail",vehicleType:="RAIL-DEFAULT"]
    ev[[in.use]][vehicleType=="ferry",vehicleType:="FERRY-DEFAULT"]
    transit.types <- c('BUS-DEFAULT','FERRY-DEFAULT','TRAM-DEFAULT','RAIL-DEFAULT','CABLE_CAR-DEFAULT','SUBWAY-DEFAULT')
    ev[[in.use]][,tripmode:=ifelse(mode%in%c('subway','bus','rail','tram','walk_transit','drive_transit','cable_car','ferry'),'transit',as.character(mode))]
    ev[[in.use]][tripmode=='car' & substr(vehicleType,nchar(as.character(vehicleType))-2,nchar(as.character(vehicleType)))=="_L5",tripmode:='cav']
    ev[[in.use]][tripmode=='ride_hail' & numPassengers>1,tripmode:='ride_hail_pooled']
    ev[[in.use]][,hour:=time/3600]
    ev[[in.use]][,hr:=round(hour)]
    setkey(ev[[in.use]],vehicleType)
    if(is.factor(ev[[in.use]]$startX[1]))ev[[in.use]][,startX:=as.numeric(as.character(startX))]
    if(is.factor(ev[[in.use]]$endX[1]))ev[[in.use]][,endX:=as.numeric(as.character(endX))]
    if(is.factor(ev[[in.use]]$startY[1]))ev[[in.use]][,startY:=as.numeric(as.character(startY))]
    if(is.factor(ev[[in.use]]$endY[1]))ev[[in.use]][,endY:=as.numeric(as.character(endY))]
    if(is.factor(ev[[in.use]]$numPassengers[1]))ev[[in.use]][,numPassengers:=as.numeric(as.character(numPassengers))]
    if(is.factor(ev[[in.use]]$capacity[1]))ev[[in.use]][,capacity:=as.numeric(as.character(capacity))]
    if(is.factor(ev[[in.use]]$expectedMaximumUtility[1]))ev[[in.use]][,expectedMaximumUtility:=as.numeric(as.character(expectedMaximumUtility))]
    if(is.factor(ev[[in.use]]$fuel[1]))ev[[in.use]][,fuel:=as.numeric(as.character(fuel))]
    ev[[in.use]][startY<=0.003 | endY <=0.003,':='(startX=NA,startY=NA,endX=NA,endY=NA)]
    ev[[in.use]][length==Inf,length:=NA]
    ev[[in.use]][vehicleType%in%transit.types & !is.na(startX)  & !is.na(startY)  & !is.na(endY)  & !is.na(endY),length:=dist.from.latlon(startY,startX,endY,endX)]
    ev[[in.use]][vehicleType%in%transit.types,numPassengers:=round(numPassengers*factor.to.scale.personal.back)]
    ev[[in.use]][vehicleType%in%transit.types,capacity:=round(capacity*factor.to.scale.transit.back)]
    ev[[in.use]][numPassengers > capacity,numPassengers:=capacity]
    ev[[in.use]][,pmt:=numPassengers*length/1609]
    ev[[in.use]][is.na(pmt),pmt:=0]
    #ev[[in.use]][,expectedMaximumUtility:=expectedMaximumUtility-quantile(ev[[in.use]]$expectedMaximumUtility,probs=.001,na.rm=T)]
    #ev[[in.use]][,expectedMaximumUtility:=expectedMaximumUtility-mean(ev[[in.use]]$expectedMaximumUtility,na.rm=T)]
    ev[[in.use]][,numAlternatives:=0]
    ev[[in.use]][expectedMaximumUtility==-Inf,expectedMaximumUtility:=NA]
    ev[[in.use]][type=='ModeChoice',numAlternatives:=str_count(availableAlternatives,":")+1]
    ev[[in.use]][type=='ModeChoice',carSurplus:=log(exp(-length/1609/45*val.of.time))]
    ev[[in.use]][type=='ModeChoice',access:=expectedMaximumUtility-carSurplus]
    ev[[in.use]][tripmode%in%c('car','bike','walk'),':='(numPassengers=1)]
    ev[[in.use]][,pmt:=numPassengers*length/1609]
    ev[[in.use]][is.na(pmt),pmt:=0]
  }
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
                   'Ride Hail - Pooled'='ride_hail_pooled',
                   'Ride Hail - Transit'='ride_hail_transit',
                   'Cable Car'='cable_car',
                   'Car'='car',
                   'CAV'='cav',
                   'Walk'='walk',
                   'Bike'='bike',
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
mode.colors <- c('Ride Hail'='red',Car='grey',Walk='green',Transit='blue','Ride Hail - Transit'='light.purple','Ride Hail - Pooled'='purple','CAV'='light.yellow','Bike'='light.orange')
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
