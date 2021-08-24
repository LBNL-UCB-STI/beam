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

workDir <- normalizePath("~/Data/GEMINI")
activitySimDir <- normalizePath("~/Data/ACTIVITYSIM")

source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oaklandMap <- ggmap::get_googlemap("oakland california", zoom = 13, maptype = "roadmap")
shpFile <- pp(workDir, "/shapefile/Oakland+Alameda+TAZ/Transportation_Analysis_Zones.shp")
oaklandCbg <- st_read(shpFile)

###
#eventsraw <- readCsv(pp(workDir, "/2021Aug22-Oakland/BASE0/events-raw/2.events.BASE0.csv.gz"))
events <- readCsv(pp(workDir, "/2021Aug22-Oakland/BASE0/events/filtered.2.events.BASE0.csv.gz"))


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
  file = pp(workDir, "/2021Aug22-Oakland/BASE0/oakland_rh_chargingEvents.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="0")

#################### PEV
refuel <- events[type%in%c("RefuelSessionEvent")&!startsWith(person,"rideHail")][
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


# trips <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010-cut-718k-by-shapefile/trips.csv.gz"))
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
  file = pp(workDir, "/2021Aug22-Oakland/BASE0/oakland_charging_events_merged_with_urbansim_tripIds.csv"),
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
# write.csv(
#   oakland_charging_events_merged_with_urbansim_tripIds_scaledUpby10,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/oakland_charging_events_merged_with_urbansim_tripIds_scaledUpby10.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
##

# ggmap(oaklandMap) +
#   theme_marain() +
#   geom_sf(data = chargingEventsSf, aes(color = as.character(parkingZoneId)), inherit.aes = FALSE) +
#   labs(color = "TAZs")
############


# countyNames <- c('Alameda County','Contra Costa County','Marin County','Napa County','Santa Clara County','San Francisco County','San Mateo County','Sonoma County','Solano County')
# counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
# ggplot() +
#   theme_marain() +
#   geom_polygon(data=counties, mapping=aes(x=long,y=lat,group=group), fill="white", size=.2) +
#   coord_map(projection = 'albers', lat0=39, lat1=45,xlim=c(-122.78,-121.86),ylim=c(37.37,38.17))+
#   geom_point(dat=toplot,aes(x=x2,y=y2,size=mw,stroke=0.5,group=grp,color=mw),alpha=.3)+
#   scale_color_gradientn(colours=c("darkgrey", "gold", "salmon", "orange", "red"), breaks=c(0.5,1,2,5)) +
#   scale_size_continuous(range=c(0.5,35), breaks=c(0.5,1,2,5))+
#   #scale_colour_continuous(breaks=c(999,5000,5001), values=c('darkgrey','orange','red'))+
#   #scale_size_continuous(range=c(0.5,35), breaks=c(999,5000,5001))+
#   labs(title="EV Charging Loads",colour='Load (MW)',size='Load (MW)',x="",y="")+
#   theme(panel.background = element_rect(fill = "#d4e6f2"),
#         legend.title = element_text(size = 20),
#         legend.text = element_text(size = 20),
#         axis.text.x = element_blank(),
#         axis.text.y = element_blank(),
#         axis.ticks.x = element_blank(),
#         axis.ticks.y = element_blank())