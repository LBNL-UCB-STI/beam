library(tidyverse)
library(data.table)

setClass("loadInfo", slots=list(timebinInSec="numeric", siteXFCInKW="numeric", plugXFCInKW="numeric"))

time.bins <- data.table(time=seq(0,61,by=0.25)*3600,quarter.hour=seq(0,61,by=0.25))
nextTimePoisson <- function(rate) {
  return(-log(1.0 - runif(1)) / rate)
}
scaleUPSession <- function(DT, t, factor) {
  nb <- nrow(DT)
  nb.scaled <- nb*factor
  rate <- nb.scaled/0.25
  DT.temp1 <- data.table(start.time2=round(t+cumsum(unlist(lapply(rep(rate, nb.scaled), nextTimePoisson)))*3600))[order(start.time2),]
  DT.temp2 <- DT[sample(.N,nrow(DT.temp1),replace=T)][order(start.time)]
  DT.temp1[,row2:=1:.N]
  DT.temp2[,row2:=1:.N]
  return(DT.temp1[DT.temp2, on="row2"])
}
extractChargingSessions <- function(events) {
  ## replace everything by chargingPointType, when develop problem is solved
  ## c("vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType", "locationY", "locationX", "duration", "vehicleType")
  ev1 <- events[type %in% c("RefuelSessionEvent")][order(time),`:=`(IDX = 1:.N),by=vehicle]
  ev2 <- events[type %in% c("ChargingPlugInEvent")][,c("vehicle", "time")][order(time),`:=`(IDX = 1:.N),by=vehicle]
  setnames(ev2, "time", "start.time")
  ev <- ev1[ev2, on=c("vehicle", "IDX")]
  return(ev)
}
spreadChargingSessionsIntoPowerIntervals <- function(ev) {
  ev[,kw:=unlist(lapply(str_split(as.character(chargingPointType),'\\('),function(ll){ as.numeric(str_split(ll[2],'\\|')[[1]][1])}))]
  ev[,depot:=(substr(vehicle,0,5)=='rideH' & substr(vehicleType,0,5)=='ev-L5')]
  ev[,plug.xfc:=(kw>=250)]
  sessions <- ev[chargingPointType!='None' & time/3600>=4
                 ,.(start.time,depot,plug.xfc,taz=parkingTaz,kw,
                    x=locationX,y=locationY,duration=duration/60,chargingPointType,
                    parkingType,vehicleType,vehicle,person,fuel,parkingZoneId)]
  sessions[,row:=1:.N]
  start.time.dt <- data.table(time=sessions$start.time)
  sessions[,start.time.bin:=time.bins[start.time.dt,on=c(time="time"),roll='nearest']$quarter.hour]
  sessions[,taz:=as.numeric(as.character(taz))]
  return(sessions)
}
scaleUpAllSessions <- function(DT, expansion.factor) {
  sim.events <- data.table()
  for (bin in seq(min(DT$start.time.bin),max(DT$start.time.bin),by=0.25))
  {
    DT.bin <- DT[start.time.bin == bin]
    sim.events <- rbind(sim.events, scaleUPSession(DT.bin, bin*3600, expansion.factor))
  }
  sim.events$start.time <- sim.events$start.time2
  sim.events$row <- paste(sim.events$row2,sim.events$row,sep="-")
  sim.events <- sim.events[,-c("row2","start.time2")]
  return(sim.events)
}
filterEvents <- function(dataDir, filename, eventsList) {
  outputFilepath <- paste(dataDir,"/events/filtered.",filename, sep="")
  if(!file.exists(outputFilepath)) {
    events <- readCsv(paste(dataDir, "/events-raw", "/", filename, sep=""))
    filteredEvents <- events[type %in% eventsList][
      ,c("vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType",
         "locationY", "locationX", "duration", "vehicleType", "person", "fuel",
         "parkingZoneId")]
    dir.create(file.path(dataDir, "events"), showWarnings = FALSE)
    write.csv(
      filteredEvents,
      file = outputFilepath,
      row.names=FALSE,
      quote=FALSE,
      na="0")
    return(filteredEvents)
  } else {
    return(readCsv(outputFilepath))
  }
}
processEventsFileAndScaleUp <- function(dataDir, scaleUpFlag, expFactor) {
  eventsRawDir <- paste(dataDir, "/events-raw",sep="")
  fileList <- list.files(path=eventsRawDir)
  for (i in 1:length(fileList)){
    name <- unlist(strsplit(fileList[i], "\\."))
    print(paste("Filtering ", fileList[i], sep=""))
    filteredEvent <- filterEvents(
      dataDir,
      fileList[i], c(
        "RefuelSessionEvent",
        "ChargingPlugInEvent",
        "ChargingPlugOutEvent",
        "actstart"))
    resultsFile <- paste("gemini.sim",name[3],"csv",sep=".")
    if (!file.exists(paste(resultsDir,resultsFile,sep="/"))) {
      print("Process charging events")
      chargingEvents <- extractChargingSessions(filteredEvent)
      simEvents <- chargingEvents
      if(scaleUpFlag) {
        print("scaling up charging events...")
        simEvents <- scaleUpAllSessions(chargingEvents, expFactor)
      }
      print("Spreading charging events into power sessions")
      sessions <- spreadChargingSessionsIntoPowerIntervals(simEvents)
      resultsDir <- paste(dataDir, "/results",sep="")
      dir.create(file.path(dataDir, "results"), showWarnings = FALSE)
      print("Writing to Disk")
      write.csv(
        sessions,
        file = paste(resultsDir,resultsFile,sep="/"),
        row.names=FALSE,
        quote=FALSE,
        na="0")
    }
  }
}


## *****************
extractLoads <- function(sessions, loadTypes, loadInfo, countyNames) {
  hourShare <- loadInfo@timebinInSec/3600.0
  siteXFCInKW <- loadInfo@siteXFCInKW
  plugXFCInKW <- loadInfo@plugXFCInKW
  # here we expand each session into the appropriate number of 15-minute bins, so each row here is 1 15-minute slice of a session
  sessions[,plug.xfc:=grepl("(250.0|DC)", chargingPointType)]
  loads <- sessions[,.(chargingPointType,depot,plug.xfc,taz,kw=c(rep(kw,length(seq(0,duration/60,by=hourShare))-1),kw*(duration/60-max(seq(0,duration/60,by=hourShare)))/hourShare),x,y,duration,hour.bin=start.time.bin+seq(0,duration/60,by=hourShare)),by='row']
  loads[,site.xfc:=(sum(kw)>=siteXFCInKW),by=c('depot','taz','hour.bin')]
  loads[,xfc:=site.xfc|plug.xfc]
  loads[,fuel:=kw*0.25/3.6e6] # the 0.25 converts avg. power in 15-minutes to kwh, then 3.6e6 converts to Joules
  loads <- loads[,.(x=x[1],y=y[1],fuel=sum(fuel),kw=sum(kw,na.rm=T),site.xfc=site.xfc[1]),by=c('depot','taz','hour.bin','xfc','chargingPointType')]
  taz <- loads[,.(x2=mean(x),y2=mean(y)),by='taz']
  loads <- merge(loads,taz,by='taz')
  loads[,grp:=paste(depot,'-',taz)]
  loads <- merge(loadTypes, loads, by='chargingPointType')
  loads[,site:=ifelse(depot == T,'depot','public')]
  counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
  setkey(loads,xfc)
  loads[,extreme.lab:=ifelse(kw >= 1000,'1-5MW','<1MW')]
  loads[kw > 5000]$extreme.lab <- ">5MW"
  return(loads)
}


## *****************
generateReadyToPlot <- function(resultsDirName, loadTypes, loadInfo, countyNames) {
  chargingTypes.colors <- c("#66CCFF", "#669900", "#660099", "#FFCC33", "#CC3300", "#0066CC")
  names(chargingTypes.colors) <- c("DCFC", "Public-L2", "Work-L2", "Work-L1", "Home-L2", "Home-L1")
  file.list <- list.files(path=resultsDirName)
  all.sessions <- list()
  all.chargingTypes <- list()
  all.loads <- list()
  dir.create(paste(resultsDirName,"../results.temp",sep="/"), showWarnings = FALSE)
  for (i in 1:length(file.list)){
    sim.xfc.file <- paste(resultsDirName,file.list[i],sep="/")
    sim.xfc.temp.file <- paste(resultsDirName,"../results.temp",file.list[i],sep="/")
    print(sim.xfc.file)
    sessions <- data.table()
    loads <- data.table()
    chargingTypes <- data.table()
    if (!file.exists(pp(sim.xfc.temp.file,"-loads.csv"))) {
      code <- unlist(strsplit(sim.xfc.file, "\\."))[3]

      sessions <- readCsv(sim.xfc.file)
      sessions[(depot),taz:=-kmeans(sessions[(depot)][,.(x,y)],20)$cluster]
      sessions[,code:=code]
      write.csv(sessions,file = pp(sim.xfc.temp.file,"-sessions.csv"),row.names=FALSE,quote=FALSE,na="0")

      loads <- extractLoads(sessions, loadTypes, loadInfo, countyNames)
      loads[,hour.bin2:=hour.bin%%24]
      loads[,code:=code]
      write.csv(loads,file = pp(sim.xfc.temp.file,"-loads.csv"),row.names=FALSE,quote=FALSE,na="0")
    } else {
      sessions <- readCsv(pp(sim.xfc.temp.file,"-sessions.csv"))
      loads <- readCsv(pp(sim.xfc.temp.file,"-loads.csv"))
    }
    all.sessions <- rbind(all.sessions, sessions)
    all.loads <- rbind(all.loads, loads)
  }
  all.sessions <- as.data.table(all.sessions)
  all.loads <- as.data.table(all.loads)
  all.loads[,type:=ifelse(site=='depot','Ridehail Depot','Public')]
  all.loads[,severity:=paste(type,extreme.lab, sep=" ")]
  save(all.sessions,all.loads,chargingTypes.colors,file=pp(resultsDirName,'/ready-to-plot.Rdata'))
}
