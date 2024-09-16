setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
source("gemini-utils.R")
library('colinmisc')
library(dplyr)
library(ggplot2)
library(rapport)
library(sjmisc)
library(ggmap)
library(sf)
library(stringr)
library(hrbrthemes)

workDir <- normalizePath("~/Workspace/Data/GEMINI")
activitySimDir <- normalizePath("~/Workspace/Data/ACTIVITYSIM")
# source("~/Workspace/Models/scripts/common/keys.R")
# register_google(key = google_api_key_1)
# oaklandMap <- ggmap::get_googlemap("oakland california", zoom = 13, maptype = "roadmap")
# shpFile <- pp(workDir, "/shapefile/Oakland+Alameda+TAZ/Transportation_Analysis_Zones.shp")
# oaklandCbg <- st_read(shpFile)
###

lognormal <- function(m, v, sample_size) {
  phi <- sqrt(v + m^2);
  mu <- log((m^2)/phi)
  sigma <- sqrt(log((phi^2)/(m^2)))
  x <- rnorm(sample_size, mean=mu, sd=sigma)
  exp(x)
}

process_evs <- function(DATA_) {
  DATA_$isRideHail <- FALSE
  DATA_[startsWith(vehicle,"rideHail")]$isRideHail <- TRUE
  DATA_$vehicleType2 <- "TRANSIT"
  DATA_[startsWith(vehicleType, "ev-")]$vehicleType2 <- "PEV"
  DATA_[startsWith(vehicleType, "ev-")&startsWith(vehicle,"rideHail")]$vehicleType2 <- "PEV-RH"
  DATA_[startsWith(vehicleType, "phev-")]$vehicleType2 <- "PEV"
  DATA_[startsWith(vehicleType, "phev-")&startsWith(vehicle,"rideHail")]$vehicleType2 <- "PEV-RH"
  DATA_[startsWith(vehicleType, "hev-")]$vehicleType2 <- "CONV"
  DATA_[startsWith(vehicleType, "hev-")&startsWith(vehicle,"rideHail")]$vehicleType2 <- "CONV-RH"
  DATA_[startsWith(vehicleType, "conv-")]$vehicleType2 <- "CONV"
  DATA_[startsWith(vehicleType, "conv-")&startsWith(vehicle,"rideHail")]$vehicleType2 <- "CONV-RH"
  DATA_[startsWith(vehicleType, "BODY-")]$vehicleType2 <- "WALK"
  DATA_[startsWith(vehicleType, "BIKE-")]$vehicleType2 <- "BIKE"
  return (DATA_)
}

###

eventsTest <- readCsv(pp(workDir, "/2022-07-05/events/filtered.0.events.5bBase.csv.gz"))
res <- eventsTest[(startsWith(vehicleType, "phev-")|startsWith(vehicleType, "ev-"))&!startsWith(vehicle, "rideHailVehicle")]
res[startsWith(vehicle, "rideHailVehicle")]
#eventsTest <- process_evs(events6HighEV)
eventsTest_rs <- eventsTest[type=="RefuelSessionEvent"][,starTime:=time-duration]
eventsTest_rs_sum <- eventsTest_rs[,.(count=.N),by=.(starTimeBin=as.integer(starTime/3600))]
eventsTest_rs_sum$scenario <- "Test"
eventsTest_rf[,.N,by=.(chargingPointType)]


#
infra <- readCsv(pp(workDir, "/2022-04/infrastructure/4a_output_2022_Apr_13_pubClust.csv"))
infra[, c("GEOM", "locationX", "locationY") := tstrsplit(geometry, " ", fixed=TRUE)]
infra <- infra[,-c("geometry", "GEOM")]
write.csv(
  infra,
  file = pp(workDir, "/2022-04/infrastructure/4a_output_2022_Apr_13_pubClust_XY.csv"),
  row.names=FALSE,
  quote=FALSE)



## 
default.ref <- readCsv(pp(workDir,"/default.0.events.pt.csv.gz"))
enroute.ref <- readCsv(pp(workDir,"/enroute.0.events.pt.csv.gz"))

default.ref.sum <- default.ref[,.(sumFuel=sum(fuel)),by=chargingPointType]
enroute.ref.sum <- enroute.ref[,.(sumFuel=sum(fuel)),by=chargingPointType]

default.ref.sum$shareFuel <- default.ref.sum$sumFuel/sum(default.ref.sum$sumFuel)
enroute.ref.sum$shareFuel <- enroute.ref.sum$sumFuel/sum(enroute.ref.sum$sumFuel)

default.ref.sum$scenario <- "DestinationOnly"
enroute.ref.sum$scenario <- "Enroute"

ref <- data.table::data.table(rbind(default.ref.sum,enroute.ref.sum))

ref %>%
  ggplot(aes(chargingPointType, shareFuel, fill=scenario)) +
  geom_bar(stat='identity',position='dodge') +
  theme_marain() +
  labs(x = "Charging Point", y = "Share (%)", fill="Capability") +
  theme(axis.text.x = element_text(angle = 30, hjust=1), strip.text = element_text(size=rel(1.2)))

fc.labels <- c("publicfc(150.0|DC)","publicxfc(250.0|DC)")
default.ref.fc <- sum(ref[scenario=="DestinationOnly"&chargingPointType%in%fc.labels]$sumFuel)
enroute.ref.fc <- sum(ref[scenario=="Enroute"&chargingPointType%in%fc.labels]$sumFuel)
default.ref.nonfc <- sum(ref[scenario=="DestinationOnly"&!chargingPointType%in%fc.labels]$sumFuel)
enroute.ref.nonfc <- sum(ref[scenario=="Enroute"&!chargingPointType%in%fc.labels]$sumFuel)

ref.change <- data.table::data.table(
  scenario=c("AC Level1/2","DC Fast"),
  relativeEnergy=c((enroute.ref.nonfc/default.ref.nonfc)-1,(enroute.ref.fc/default.ref.fc)-1)
)

p <- ref.change %>%
  ggplot(aes(scenario, relativeEnergy, fill=scenario)) +
  geom_bar(stat='identity',position='dodge') +
  theme_marain() +
  labs(x = "Charging Point Type", y = "Enroute Energy wrt DestinationOnly") +
  guides(fill="none")
ggsave(pp(workdir,'/Relative-energy-charged.png'),p,width=4,height=5,units='in')

ref$chargingPoint <- "Level1/2"
ref[chargingPointType=="dcfast(50.0|DC)"]$chargingPoint <- "Fast"
ref[chargingPointType=="ultrafast(250.0|DC)"]$chargingPoint <- "XFC"

chargingPoint_order <- c("Level1/2","Fast","XFC")
ref %>%
  ggplot(aes(factor(chargingPoint, levels=chargingPoint_order), shareFuel, fill=scenario)) +
  geom_bar(stat='identity',position='dodge') +
  theme_marain() +
  labs(x = "Charging Point", y = "Share (%)", fill="Capability")



#################### REV
rh_refueling <- events[type == "RefuelSessionEvent" & startsWith(vehicle,"rideHail")][
  ,.(person,startTime=time-duration,startTime2=time-duration,parkingTaz,chargingPointType,
     pricingModel,parkingType,locationX,locationY,vehicle,vehicleType,fuel,duration)][
       ,`:=`(stallLocationX=locationX,stallLocationY=locationY)]
rh_refueling_asSF <- st_as_sf(
  rh_refueling,
  coords = c("locationX", "locationY"),
  crs = 4326,
  agr = "constant")
oakland_rh_chargingEvents <- st_intersection(rh_refueling_asSF, oaklandCbg)
st_geometry(oakland_rh_chargingEvents) <- NULL
oakland_rh_chargingEvents <- data.table::as.data.table(oakland_rh_chargingEvents)

write.csv(
  oakland_rh_chargingEvents,
  file = pp(workDir, "/2021Aug22-Oakland/BATCH3/oakland_rh_chargingEvents.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="0")

#################### PEV
refuel <- events[type%in%c("RefuelSessionEvent")][
  ,.(person,startTime=time-duration,startTime2=time-duration,parkingTaz,chargingPointType,
     pricingModel,parkingType,locationX,locationY,vehicle,vehicleType,fuel,duration)]
actstart <- events[type%in%c("actstart")&!startsWith(person,"rideHail")][
  ,.(person, actTime = time, actTime2 = time, actType)]
refuel_actstart <- refuel[
  actstart,on=c(person="person",startTime2="actTime2"),mult="first",roll="nearest"][
    ,-c("startTime2")][is.na(startTime)|abs(actTime-startTime)<1705][order(actTime),`:=`(IDX = 1:.N),by=.(person,actType)]
refuel_actstart$person <- as.character(refuel_actstart$person)
# write.csv(
#   refuel_actstart,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/refuel_actstart.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
# refuel_actstart <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/refuel_actstart.csv"))


plans <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010/plans.csv.gz"))
trips <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010/trips.csv.gz"))
# persons <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010-cut-718k-by-shapefile/persons.csv.gz"))
# households <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010-cut-718k-by-shapefile/households.csv.gz"))
# blocks <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010-cut-718k-by-shapefile/blocks.csv.gz"))



refueling_person_ids <- unique(refuel_actstart$person)
plans <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010-cut-718k-by-shapefile/plans.csv.gz"))
plans$person_id <- as.character(plans$person_id)
plans_filtered <- plans[person_id %in% refueling_person_ids]
plans_leg_act_merge_temp <- plans_filtered[
  order(person_id,-PlanElementIndex),
  .(person = person_id,
    tripId = .SD[.I+1]$trip_id,
    numberOfParticipants = .SD[.I+1]$number_of_participants,
    tripMode = .SD[.I+1]$trip_mode,
    actType = ActivityType,
    actLocationX = x,
    actLocationY = y,
    departureTime = departure_time)
  , ]
plans_leg_act_merge_temp[is.na(departureTime)]$departureTime <- 32
plans_leg_act_merge  <- plans_leg_act_merge_temp[
  !(is.na(tripId)|tripId=="")][
    order(departureTime),`:=`(IDX = 1:.N),by=.(person,actType)]
# write.csv(
#   plans_leg_act_merge,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/plans_leg_act_merge.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
# plans_leg_act_merge <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/plans_leg_act_merge.csv"))

charging_events_merged_with_urbansim_tripIds <- refuel_actstart[
  plans_leg_act_merge, on=c("person", "IDX", "actType")][
    !is.na(startTime)][,`:=`(stallLocationX=locationX,stallLocationY=locationY)]
charging_events_merged_with_urbansim_tripIds_asSf <- st_as_sf(
  charging_events_merged_with_urbansim_tripIds,
  coords = c("locationX", "locationY"),
  crs = 4326,
  agr = "constant")
oakland_charging_events_merged_with_urbansim_tripIds <- st_intersection(
  charging_events_merged_with_urbansim_tripIds_asSf,
  oaklandCbg)
st_geometry(oakland_charging_events_merged_with_urbansim_tripIds) <- NULL
oakland_charging_events_merged_with_urbansim_tripIds <- data.table::as.data.table(oakland_charging_events_merged_with_urbansim_tripIds)

write.csv(
  oakland_charging_events_merged_with_urbansim_tripIds,
  file = pp(workDir, "/2021Aug22-Oakland/BATCH3/oakland_charging_events_merged_with_urbansim_tripIds.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="0")



## SCALE UP ******
#oakland_charging_events_merged_with_urbansim_tripIds <- readCsv(pp(workDir, "/2021Aug22-Oakland/BASE0/oakland_charging_events_merged_with_urbansim_tripIds.csv"))
sessions <- oakland_charging_events_merged_with_urbansim_tripIds
sessions$start.time <- sessions$startTime
start.time.dt <- data.table(time=sessions$start.time)
sessions[,start.time.bin:=time.bins[start.time.dt,on=c(time="time"),roll='nearest']$quarter.hour]

expFactor <- (6.015/0.6015)
oakland_charging_events_merged_with_urbansim_tripIds_scaledUpby10 <- scaleUpAllSessions(sessions, expFactor)


###########




