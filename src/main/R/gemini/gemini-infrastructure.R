setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
source("gemini-utils.R")
library('colinmisc')
library(dplyr)
library(ggplot2)

dataDir <- normalizePath("~/Workspace/Data")
geminiDir <- normalizePath("~/Workspace/Data/GEMINI")
infraDir <- pp(geminiDir,"/2022-07-05/_models/infrastructure")

network <-readCsv(pp(geminiDir, "/network.csv.gz"))

infra5aBase <- readCsv(pp(infraDir, "/4a_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))
infra5bBase <- readCsv(pp(infraDir, "/4b_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))
infra6Base <- readCsv(pp(infraDir, "/6_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))

aggregateInfrastructure <- function(FILE_NAME) {
  DATA <- readCsv(pp(infraDir, "/", FILE_NAME, ".csv"))
  DATA_agg <- DATA[,.(feeInCents=mean(feeInCents),numStalls=sum(numStalls)),by=.(taz,parkingType,chargingPointType,reservedFor,pricingModel)]
  DATA_agg <- DATA_agg[,`:=`(IDX = 1:.N),by=taz]
  DATA_agg$parkingZoneId <- paste("site",DATA_agg$taz,DATA_agg$IDX,sep="-")
  DATA_agg <- DATA_agg[,-c("IDX")]
  DATA_merge <- merge(DATA, DATA_agg, by=c("taz","parkingType","chargingPointType","reservedFor","pricingModel"), all = TRUE)
  setnames(DATA_merge, "parkingZoneId.x", "parkingZoneId")
  setnames(DATA_merge, "feeInCents.x", "feeInCents")
  setnames(DATA_merge, "numStalls.x", "numStalls")
  setnames(DATA_merge, "parkingZoneId.y", "siteId")
  DATA_merge <- DATA_merge[,-c("feeInCents.y","numStalls.y")]
  print(pp("printing... ", pp(infraDir, "/", FILE_NAME, "_siteId.csv")))
  write.csv(
    DATA_merge,
    file = pp(infraDir, "/", FILE_NAME, "_siteId.csv"),
    row.names=FALSE,
    quote=FALSE)
  print(pp("printing... ", pp(infraDir, "/", FILE_NAME, "_aggregated.csv")))
  write.csv(
    DATA_agg,
    file = pp(infraDir, "/", FILE_NAME, "_aggregated.csv"),
    row.names=FALSE,
    quote=FALSE)
  print("END aggregateInfrastructure")
}

aggregateInfrastructure("4b_output_2022_Apr_13_pubClust_withFees_noHousehold")

aggregateInfrastructure("6_output_2022_Apr_13_pubClust_withFees_noHousehold")





infra5bBase_agg <- infra5bBase[,.(feeInCents=mean(feeInCents),numStalls=sum(numStalls)),by=.(taz,parkingType,chargingPointType,reservedFor,pricingModel)]
infra5bBase_agg <- infra5bBase_agg[,`:=`(IDX = 1:.N),by=taz]
infra5bBase_agg$parkingZoneId <- paste(infra5bBase_agg$taz,infra5bBase_agg$IDX,sep="-")
infra5bBase_agg <- infra5bBase_agg[,-c("IDX")]
infra5bBase_merge <- merge(infra5bBase, infra5bBase_agg, by=c("taz","parkingType","chargingPointType","reservedFor","pricingModel"), all = TRUE)

infra5bBase_merge <- infra5bBase_merge[,c-("")]
setnames(infra5bBase_merge, "parkingZoneId.x", "parkingZoneId")
setnames(infra5bBase_merge, "feeInCents.x", "feeInCents")
setnames(infra5bBase_merge, "numStalls.x", "numStalls")
setnames(infra5bBase_merge, "parkingZoneId.y", "siteId")
infra5bBase_merge <- infra5bBase_merge[,-c("feeInCents.y","numStalls.y")]
write.csv(
  infra5bBase_merge,
  file = pp(infraDir, "/6_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"),
  row.names=FALSE,
  quote=FALSE)




infra5aBase <- readCsv(pp(workDir, "/2022-04-28/_models/infrastructure/4a_output_2022_Apr_13_pubClust_withFees_aggregated.csv"))
infra5bBase <- readCsv(pp(workDir, "/2022-04-28/_models/infrastructure/6_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))
infra5bBase[startsWith(reservedFor,"household")]$reservedFor <- "Any"
infra5bBase_NH <- infra5bBase[
  ,.(parkingZoneId=first(parkingZoneId),feeInCents=mean(feeInCents),numStalls=sum(numStalls))
  ,b=.(taz,parkingType,chargingPointType,X,Y,reservedFor,pricingModel)]
write.csv(
  infra5bBase_NH,
  file = pp(workDir, "/2022-04-28/_models/infrastructure/6_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"),
  row.names=FALSE,
  quote=FALSE)

vehicles1 <- readCsv(pp(workDir, "/vehicles.4Base.csv"))
vehicles1$stateOfCharge <- as.double(vehicles1$stateOfCharge)
vehicles1[is.na(stateOfCharge)]$stateOfCharge <- 1.0
vehicles1[is.infinite(stateOfCharge)]$stateOfCharge <- 1.0

vehicles2 <- readCsv(pp(workDir, "/2.final_vehicles.5bBase.csv"))
vehicles2$stateOfCharge <- as.double(vehicles2$stateOfCharge)
vehicles2[is.na(stateOfCharge)]$stateOfCharge <- 0.5
vehicles2[is.infinite(stateOfCharge)]$stateOfCharge <- 0.5
vehicles2$stateOfCharge <- abs(vehicles2$stateOfCharge)
vehicles2[stateOfCharge < 0.2]$stateOfCharge <- 0.2
vehicles2[stateOfCharge > 1]$stateOfCharge <- 1.0

vehicles3 <- rbind(vehicles1, vehicles2)[
  ,.(stateOfCharge=min(stateOfCharge))
  ,by=.(vehicleId,vehicleTypeId,householdId)]

write.csv(
  vehicles3,
  file = pp(workDir, "/new.vehicles.5bBase.csv"),
  row.names=FALSE,
  quote=FALSE)


#### SITE CAPACITY

sites <- infra5bBase[,.(numStalls=sum(numStalls)),by=.(taz,parkingType,chargingPointType,reservedFor,pricingModel)]

sites[,kw:=unlist(lapply(str_split(as.character(chargingPointType),'\\('),function(ll){ as.numeric(str_split(ll[2],'\\|')[[1]][1])}))]
sites[,kwTot:=kw*numStalls]
sites[,plug.xfc:=(kw>=200)]
sites[,site.xfc:=(kwTot>=1000)]
nrow(sites)
nrow(sites[plug.xfc == TRUE | site.xfc == TRUE])
nrow(sites[plug.xfc == TRUE & site.xfc == TRUE])
nrow(sites[plug.xfc == TRUE])
nrow(sites[site.xfc == TRUE])

fast_sites <- sites[kw >= 100]
nrow(fast_sites)
nrow(fast_sites[plug.xfc == TRUE | site.xfc == TRUE])
nrow(fast_sites[plug.xfc == TRUE & site.xfc == TRUE])
nrow(fast_sites[plug.xfc == TRUE])
nrow(fast_sites[site.xfc == TRUE])


sites[plug.xfc]


####

events <- readCsv(pp(geminiDir,"/2022-07-05/events/filtered.0.events.8MaxEV.csv.gz"))
events.sim <- readCsv(pp(geminiDir, "/2022-07-05/sim/events.sim.7Advanced.csv.gz"))

refueling <- events[type == "RefuelSessionEvent"][
  ,.(person,startTime=time-duration,startTime2=time-duration,parkingTaz,chargingPointType,
     pricingModel,parkingType,locationX,locationY,vehicle,vehicleType,fuel,duration,actType)][
       ,`:=`(stallLocationX=locationX,stallLocationY=locationY)]

write.csv(
  refueling[!startsWith(chargingPointType, "depot")],
  file = pp(geminiDir,"/2022-07-05/_models/chargingEvents.8MaxEV.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="0")

# chargingBehaviorFunc2(refueling)


hongcai_siting_input <- readCsv(pp(dataDir,"/DepotSiting/hczhang/beam_ev_rhrf_outputs.csv"))
