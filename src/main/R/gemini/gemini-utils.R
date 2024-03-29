library(tidyverse)
library(data.table)

nbOfBinsInHour <- 3600/900
binsInterval <- 1/nbOfBinsInHour
siteXFCInKW <- 1000
plugXFCInKW <- 200
time.bins <- data.table(time=seq(0,61,by=binsInterval)*3600,quarter.hour=seq(0,61,by=binsInterval))

#chargingTypes.colors <- c("goldenrod2", "#66CCFF", "#669900", "#660099", "#FFCC33", "#CC3300", "#0066CC")
#names(chargingTypes.colors) <- c("XFC", "DCFC", "Public-L2", "Work-L2", "Work-L1", "Home-L2", "Home-L1")
chargingTypes.colors <- c("goldenrod2", "#66CCFF", "#669900", "#660099", "#CC3300", "#0066CC")
names(chargingTypes.colors) <- c("XFC", "DCFC", "Public-L2", "Work-L2", "Home-L2", "Home-L1")
loadTypes <- data.table::data.table(
  chargingPointType = c(
    "homelevel1(1.8|AC)", "homelevel2(7.2|AC)",
    "worklevel2(7.2|AC)",
    "publiclevel2(7.2|AC)",
    "publicfc(50.0|DC)", "publicxfc(50.0|DC)", "publicfc(150.0|DC)", "depotfc(150.0|DC)",
    "depotxfc(200.0|DC)", "depotxfc(300.0|DC)", "depotxfc(400.0|DC)",
    "publicfc(200.0|DC)", "publicxfc(200.0|DC)", "publicxfc(300.0|DC)", "publicxfc(400.0|DC)", "publicxfc(250.0|DC)"),
  loadType = c("Home-L1", "Home-L2",
               "Work-L2",
               "Public-L2",
               "DCFC", "DCFC", "DCFC", "DCFC",
               "XFC", "XFC", "XFC",
               "XFC", "XFC", "XFC", "XFC", "XFC"))

nextTimePoisson <- function(rate) {
  return(-log(1.0 - runif(1)) / rate)
}
scaleUPSession <- function(DT, t, factor) {
  nb <- nrow(DT)
  nb.scaled <- nb*factor
  rate <- nb.scaled/binsInterval
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
  ev1[, start.time:=time-duration]
  #ev1.vehicles <- unique(ev1$vehicle)
  #ev2 <- events[vehicle%in%ev1.vehicles][type %in% c("ChargingPlugInEvent")][,c("vehicle", "time")][order(time),`:=`(IDX = 1:.N),by=vehicle]
  #setnames(ev2, "time", "start.time")
  #ev <- ev1[ev2, on=c("vehicle", "IDX")][!is.na(parkingTaz)]
  ev <- ev1[!is.na(parkingTaz)]
  return(ev)
}
spreadChargingSessionsIntoPowerIntervals <- function(ev) {
  ev[,kw:=unlist(lapply(str_split(as.character(chargingPointType),'\\('),function(ll){ as.numeric(str_split(ll[2],'\\|')[[1]][1])}))]
  ev[,depot:=(substr(vehicle,0,5)=='rideH' & substr(vehicleType,0,5)=='ev-L5')]
  ev[,plug.xfc:=(kw>=250)]
  sessions <- ev[chargingPointType!='NoCharger'
                 ,.(start.time,depot,plug.xfc,taz=parkingTaz,kw,
                    x=locationX,y=locationY,duration=duration/3600.0,chargingPointType,
                    parkingType,vehicleType,vehicle,person,fuel,parkingZoneId)]
  sessions[,row:=1:.N]
  start.time.dt <- data.table(time=sessions$start.time)
  sessions[,start.time.bin:=time.bins[start.time.dt,on=c(time="time"),roll='nearest']$quarter.hour]
  sessions[,taz:=as.numeric(as.character(taz))]
  return(sessions)
}
scaleUpAllSessions <- function(DT, expansion.factor) {
  sim.events <- data.table()
  for (bin in seq(min(DT$start.time.bin),max(DT$start.time.bin),by=binsInterval))
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
  outputFilepath2 <- paste(dataDir,"/events/paths.",filename, sep="")
  if(!file.exists(outputFilepath)) {
    events <- readCsv(paste(dataDir, "/events-raw", "/", filename, sep=""))
    filteredEvents <- events[type %in% eventsList][
      ,c("vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType",
         "locationY", "locationX", "duration", "vehicleType", "person", "fuel", "parkingZoneId",
         "pricingModel", "actType")]
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
      simDir <- paste(dataDir, "/sim",sep="")
      dir.create(file.path(dataDir, "sim"), showWarnings = FALSE)
      simFile <- paste("events.sim",name[3],"csv.gz",sep=".")
      write.csv(
        simEvents,
        file = paste(simDir,simFile,sep="/"),
        row.names=FALSE,
        quote=FALSE,
        na="0")
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
extractLoads <- function(sessions, loadTypes, countyNames) {
  # here we expand each session into the appropriate number of X-minute bins, so each row here is 1 X-minute slice of a session
  sessions[,plug.xfc:=grepl("xfc", chargingPointType)]
  loads <- sessions[,.(parkingZoneId,chargingPointType,depot,plug.xfc,taz,kw=c(rep(kw,length(seq(0,duration,by=binsInterval))-1),kw*(duration-max(seq(0,duration,by=binsInterval)))/binsInterval),x,y,duration,hour.bin=start.time.bin+seq(0,duration,by=binsInterval)),by='row']
  loads[,site.xfc:=(sum(kw)>=siteXFCInKW),by=c('depot','taz','hour.bin')]
  loads[,xfc:=site.xfc|plug.xfc]
  loads[,fuel:=kw*binsInterval*3.6e6] # the binsInterval converts avg. power in X-minutes to kwh, then 3.6e6 converts to Joules
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
generateReadyToPlot <- function(resultsDirName, loadTypes, countyNames) {
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

      loads <- extractLoads(sessions, loadTypes, countyNames)
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