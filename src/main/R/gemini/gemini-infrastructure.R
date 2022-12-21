setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
source("gemini-utils.R")
library('colinmisc')
library(dplyr)
library(ggplot2)

dataDir <- normalizePath("~/Workspace/Data")
projectDir <- pp(dataDir, "/GEMINI")
workDir <- pp(projectDir,"/2022-07-05")
infraDir <- pp(workDir,"/_models/nrel_infrastructure")


###########################
###########################
###########################

#infra8MaxEV <- readCsv(pp(infraDir, "/scen_8_infrastructure_withFees.csv.gz"))
infra_converted_withFees <- readCsv(pp(infraDir, "/scen_8_infrastructure_withFees.csv.gz"))
infra_converted_withFees[startsWith(reservedFor,"household")]$reservedFor <- "Any"
infra_converted_withFees_noHousehold <- infra_converted_withFees[
  ,.(parkingZoneId=first(parkingZoneId),feeInCents=mean(feeInCents),numStalls=sum(numStalls))
  ,b=.(taz,parkingType,chargingPointType,X,Y,reservedFor,pricingModel)]
names(infra_converted_withFees_noHousehold)[names(infra_converted_withFees_noHousehold) == "X"] <- "locationX"
names(infra_converted_withFees_noHousehold)[names(infra_converted_withFees_noHousehold) == "Y"] <- "locationY"
write.csv(
  infra_converted_withFees_noHousehold,
  file = pp(infraDir, "/scen_8_infrastructure_withFees_noHousehold.csv"),
  row.names=FALSE,
  quote=FALSE)

###########################

infra5aBase <- readCsv(pp(infraDir, "/4a_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))
infra5bBase <- readCsv(pp(infraDir, "/4b_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))
infra6Base <- readCsv(pp(infraDir, "/6_output_2022_Apr_13_pubClust_withFees_noHousehold.csv"))
infra7Advanced <- readCsv(pp(infraDir,"/scen_7_infrastructure_withFees_noHousehold.csv"))
infra8MaxEV <- readCsv(pp(infraDir,"/scen_8_infrastructure_withFees_noHousehold.csv"))

aggregateInfrastructure <- function(FULL_FILE_NAME) {
  DATA <- readCsv(FULL_FILE_NAME)
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
  FILE_NAME_NO_EXT <- tools::file_path_sans_ext(FULL_FILE_NAME)
  print(pp("printing... ", pp(FILE_NAME_NO_EXT, "_siteId.csv")))
  write.csv(
    DATA_merge,
    file = pp(FILE_NAME_NO_EXT, "_siteId.csv"),
    row.names=FALSE,
    quote=FALSE)
  print(pp("printing... ", pp(FILE_NAME_NO_EXT, "_aggregated.csv")))
  write.csv(
    DATA_agg,
    file = pp(FILE_NAME_NO_EXT, "_aggregated.csv"),
    row.names=FALSE,
    quote=FALSE)
  print("END aggregateInfrastructure")
}

aggregateInfrastructure(pp(infraDir, "/scen_7_infrastructure_withFees_noHousehold.csv"))

######

#####
test7Advanced <- readCsv(pp(workDir,"/_models/nrel_infrastructure/scen_7_infrastructure_withFees_noHousehold_aggregated.csv"))
test7Advanced[,.N,by=.(chargingPointType)]

test6MidTerm <- readCsv(pp(workDir,"/_models/nrel_infrastructure/6_output_2022_Apr_13_pubClust_withFees_noHousehold_aggregated.csv"))
test6MidTerm[,.N,by=.(chargingPointType)]


######

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

###########################
###########################
###########################

convertToFCSPlanInput <- function(DT, run_name) {
  DT2 <- DT[,c("time", "duration", "vehicle", "numPassengers", "length", 
               "startX", "fuel","startY", "endX", "endY", "primaryFuel", "type", 
               "departureTime", "arrivalTime", "locationX", "locationY")]
  names(DT2)[names(DT2) == "numPassengers"] <- "num_passengers"
  names(DT2)[names(DT2) == "startX"] <- "start.x"
  names(DT2)[names(DT2) == "startY"] <- "start.y"
  names(DT2)[names(DT2) == "endX"] <- "end.x"
  names(DT2)[names(DT2) == "endY"] <- "end.y"
  names(DT2)[names(DT2) == "type"] <- "type2"
  DT2[type2=="RefuelSessionEvent", start.x:=locationX]
  DT2[type2=="RefuelSessionEvent", start.y:=locationY]
  DT2[type2=="PathTraversal", time:=departureTime]
  DT2[type2=="RefuelSessionEvent", time:=time-duration]
  DT2[,kwh:=0]
  DT2[type2=="PathTraversal", kwh:=primaryFuel/3.6e+6]
  DT2[type2=="RefuelSessionEvent", kwh:=fuel/3.6e+6]
  DT2[type2=="PathTraversal", duration:=arrivalTime-departureTime]
  DT2$run <- run_name
  DT2 <- DT2[,-c("departureTime", "arrivalTime", "primaryFuel", "fuel", "locationX", "locationY")]
  DT2[,speed:=0.0]
  DT2[type2=="PathTraversal", speed:=ifelse(duration==0, NA, length/duration)*2.237]
  DT2[type2=="RefuelSessionEvent", speed:=NA]
  DT2[,reposition:=NA]
  DT2[type2=="PathTraversal", reposition:=ifelse(num_passengers==0, TRUE, FALSE)]
  DT2[,type:="Movement"]
  DT2[type2=="RefuelSessionEvent",type:="Charge"]
  DT2[,hour:=as.integer(time/3600)]
  DT2 <- DT2[,-c("type2")]
  return(DT2)
}

hongcai_siting_input <- readCsv(pp(dataDir,"/DepotSiting/hczhang/beam_ev_rhrf_outputs.csv"))

work_dir <- pp(dataDir,"/GEMINI/2022-07-05")
events_plus_dir <- pp(work_dir,"/events-plus")

gemini7Advanced <- readCsv(pp(events_plus_dir,"/rhev-siting.0.events.7Advanced.csv.gz"))
gemini8MaxEV <- readCsv(pp(events_plus_dir,"/rhev-siting.0.events.8MaxEV.csv.gz"))

gemini7AdvancedFiltered <- gemini7Advanced[startsWith(vehicleType, "ev-") | startsWith(vehicleType, "phev-")]
gemini8MaxEVFiltered <- gemini8MaxEV[startsWith(vehicleType, "ev-") | startsWith(vehicleType, "phev-")]

gemini7AdvancedNumVeh <- length(unique(gemini7AdvancedFiltered$vehicle))
gemini8MaxEVNumVeh <- length(unique(gemini8MaxEVFiltered$vehicle))


gemini7Advanced1 <- convertToFCSPlanInput(gemini7AdvancedFiltered, "chargerLevel_400kW__vehicleRange_300mi__ridehailNumber_35k")
gemini8MaxEV1 <- convertToFCSPlanInput(gemini8MaxEVFiltered, "chargerLevel_400kW__vehicleRange_300mi__ridehailNumber_74k")
gemini7Advanced2 <- convertToFCSPlanInput(gemini7AdvancedFiltered, "chargerLevel_300kW__vehicleRange_250mi__ridehailNumber_35k")
gemini8MaxEV2 <- convertToFCSPlanInput(gemini8MaxEVFiltered, "chargerLevel_300kW__vehicleRange_250mi__ridehailNumber_74k")
gemini7Advanced3 <- convertToFCSPlanInput(gemini7AdvancedFiltered, "chargerLevel_200kW__vehicleRange_200mi__ridehailNumber_35k")
gemini8MaxEV3 <- convertToFCSPlanInput(gemini8MaxEVFiltered, "chargerLevel_200kW__vehicleRange_200mi__ridehailNumber_74k")

beam_ev_gemini_outputs <- rbind(gemini7Advanced1, gemini8MaxEV1, 
                                gemini7Advanced2, gemini8MaxEV2,
                                gemini7Advanced3, gemini8MaxEV3)
write.csv(
  beam_ev_gemini_outputs,
  file = pp(events_plus_dir, "/beam_ev_gemini_outputs.csv"),
  row.names=FALSE,
  quote=FALSE)


rh7Advanced_file <- pp(workDir,"/events-plus/FCS_planning_results/taz-parking_S80_P300_R250_F35k.csv")
rh8MaxEV_file <- pp(workDir,"/events-plus/FCS_planning_results/taz-parking_S80_P300_R250_F74k.csv")

rhInfra <- readCsv(rh8MaxEV_file)
rhInfra$parkingZoneId2 <- apply(rhInfra[,c("taz","parkingZoneId")],1,paste,collapse="-")
rhInfra$parkingZoneId <- rhInfra$parkingZoneId2
rhInfra <- rhInfra[,-c("parkingZoneId2")]
write.csv(
  rhInfra,
  file = pp(workDir, "/events-plus/FCS_planning_results/taz-parking_S80_P300_R250_F74k_Bis.csv"),
  row.names=FALSE,
  quote=FALSE)





