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

#sessions <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/results/gemini.sim.BASE0.csv"))
#charging_sessions <- readCsv("~/Data/GEMINI/2021Jul30-Oakland/BASE0/2021-08-01.refuelSession.csv")
#chargingEventsSf <- st_as_sf(sessions, coords = c("x", "y"), crs = 4326, agr = "constant")
#oakland_sessions <- data.table::as.data.table(st_intersection(chargingEventsSf, oaklandCbg))
# write.csv(
#   oakland_sessions,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/0.charging_events.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")

# events <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/events/0.events.BASE0.csv.gz"))
# ppl_refueling <- events[type == "RefuelSessionEvent" &!startsWith(vehicle,"rideHail"),.N,by=person]
# refuel_actstart <- events[type%in%c("actstart","RefuelSessionEvent")&person%in%ppl_refueling$person]
# write.csv(
#   refuel_actstart,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/refuel_actstart.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
refuel_actstart <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/refuel_actstart.csv"))

refuel_actstart_cleaned <- refuel_actstart[
  ,.(person,time,type,parkingTaz,chargingType,pricingModel,parkingType,
     locationX,locationY,vehicle,actType,vehicleType,
     activityLocationX,activityLocationY,fuel,duration)]

refuel_acstart_merge_temp <- refuel_actstart_cleaned[
  order(person)
  , .(person = person,
      startTime = time,
      type = .SD[.I+1]$type,
      vehicle = .SD[.I+1]$vehicle,
      vehicleType = .SD[.I+1]$vehicleType,
      parkingTaz = .SD[.I+1]$parkingTaz,
      fuel = .SD[.I+1]$fuel,
      duration = .SD[.I+1]$duration,
      actType = actType,
      parkingType = .SD[.I+1]$parkingType,
      chargingType = .SD[.I+1]$chargingType,
      pricingModel = .SD[.I+1]$pricingModel,
      stallLocationX = .SD[.I+1]$locationX,
      stallLocationY = .SD[.I+1]$locationY)
  , ]
refuel_acstart_merge <- refuel_acstart_merge_temp[
  !(is.na(actType)|actType=="")][
  order(startTime),`:=`(IDX = 1:.N),by=person]
# write.csv(
#   refuel_acstart_merge,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/refuel_acstart_merge.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
refuel_acstart_merge <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/refuel_acstart_merge.csv"))

refueling_person_ids <- unique(refuel_acstart_merge$person)
plans <- readCsv(pp(activitySimDir, "/activitysim-plans-base-2010/plans.csv.gz"))
plans$person_id <- as.character(plans$person_id)
plans_filtered <- plans[person_id %in% refueling_person_ids]
# write.csv(
#   plans_filtered,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/plans_filtered.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
plans_filtered <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/plans_filtered.csv"))

#memory.size(max = TRUE)

plans_leg_act_merge_temp <- plans_filtered[order(person_id)
  , .(person_id = person_id,
      trip_id = .SD[.I+1]$trip_id,
      number_of_participants = .SD[.I+1]$number_of_participants,
      trip_mode = .SD[.I+1]$trip_mode,
      ActivityType = ActivityType,
      x = x,
      y = y,
      departure_time = departure_time)
  , ]
plans_leg_act_merge <- plans_leg_act_merge_temp[
  !(is.na(trip_id)|trip_id=="")][
    order(departure_time),`:=`(IDX = 1:.N),by=person_id]
# write.csv(
#   plans_leg_act_merge,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/plans_leg_act_merge.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
plans_leg_act_merge <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/plans_leg_act_merge.csv"))


charging_events_merged_with_urbansim_tripIds <- refuel_acstart_merge[
  plans_leg_act_merge, on=c("person" = "person_id", "IDX")][
    type=="RefuelSessionEvent"]
# write.csv(
#   charging_events_merged_with_urbansim_tripIds,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/charging_events_merged_with_urbansim_tripIds.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
charging_events_merged_with_urbansim_tripIds <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/charging_events_merged_with_urbansim_tripIds.csv"))

charging_events_merged_with_urbansim_tripIds$stallLocationXBis <- charging_events_merged_with_urbansim_tripIds$stallLocationX
charging_events_merged_with_urbansim_tripIds$stallLocationYBis <- charging_events_merged_with_urbansim_tripIds$stallLocationY

charging_events_merged_with_urbansim_tripIds_asSf <- st_as_sf(charging_events_merged_with_urbansim_tripIds, coords = c("stallLocationXBis", "stallLocationYBis"), crs = 4326, agr = "constant")
oakland_charging_events_merged_with_urbansim_tripIds <- st_intersection(charging_events_merged_with_urbansim_tripIds_asSf, oaklandCbg)
st_geometry(oakland_charging_events_merged_with_urbansim_tripIds) <- NULL
oakland_charging_events_merged_with_urbansim_tripIds <- data.table::as.data.table(oakland_charging_events_merged_with_urbansim_tripIds)
# write.csv(
#   oakland_charging_events_merged_with_urbansim_tripIds,
#   file = pp(workDir, "/2021Aug17-SFBay/BASE0/oakland_charging_events_merged_with_urbansim_tripIds.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
oakland_charging_events_merged_with_urbansim_tripIds <- readCsv(pp(workDir, "/2021Aug17-SFBay/BASE0/oakland_charging_events_merged_with_urbansim_tripIds.csv"))

sessions <- oakland_charging_events_merged_with_urbansim_tripIds
sessions$start.time <- sessions$startTime
start.time.dt <- data.table(time=sessions$start.time)
sessions[,start.time.bin:=time.bins[start.time.dt,on=c(time="time"),roll='nearest']$quarter.hour]

expFactor <- (6.015/0.6015)
oakland_charging_events_merged_with_urbansim_tripIds_scaledUpby10 <- scaleUpAllSessions(sessions, expFactor)
write.csv(
  oakland_charging_events_merged_with_urbansim_tripIds_scaledUpby10,
  file = pp(workDir, "/2021Aug17-SFBay/BASE0/oakland_charging_events_merged_with_urbansim_tripIds_scaledUpby10.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="0")


############
test1 <- refuel_actstart_cleaned[person == 5358084]
test[1,]
events_test[type=="RefuelSessionEvent"&startsWith(vehicle,"ride")&chargingType!="None"]


test2 <- test1[
  , .(startTime = time,
      type = .SD[.I+1]$type,
      vehicle = .SD[.I+1]$vehicle,
      vehicleType = .SD[.I+1]$vehicleType,
      parkingTaz = .SD[.I+1]$parkingTaz,
      fuel = .SD[.I+1]$fuel,
      duration = .SD[.I+1]$duration,
      actType = actType,
      parkingType = .SD[.I+1]$parkingType,
      chargingType = .SD[.I+1]$chargingType,
      pricingModel = .SD[.I+1]$pricingModel,
      locationX = .SD[.I+1]$locationX,
      locationY = .SD[.I+1]$locationY,
      activityLocationX = .SD[.I+1]$activityLocationX,
      activityLocationY = .SD[.I+1]$activityLocationY), 
      by = person]
test3 <- test2[!(is.na(actType)|actType=="")][order(startTime),`:=`(IDX = 1:.N),by=person]

plans1 <- plans[person_id %in% c(5358084, 1000427)]
plans2 <- plans1[order(person_id)
                 , .(person_id = person_id,
                     trip_id = .SD[.I+1]$trip_id,
                     number_of_participants = .SD[.I+1]$number_of_participants,
                     trip_mode = .SD[.I+1]$trip_mode,
                     trip_mode2 = trip_mode,
                     ActivityType = ActivityType,
                     x = x,
                     y = y,
                     departure_time = departure_time)
                 , 
]

plans2 <- plans1[
  , .(trip_id = .SD[.I+1]$trip_id,
      number_of_participants = .SD[.I+1]$number_of_participants,
      trip_mode = .SD[.I+1]$trip_mode,
      ActivityType = ActivityType,
      x = x,
      y = y,
      departure_time = departure_time),
  by = person_id
]
plans3 <- plans2[!is.na(trip_id)][order(departure_time),`:=`(IDX = 1:.N),by=person_id]
plans3$person_id <- as.character(plans3$person_id)

result <- test3[plans3, on=c("person" = "person_id", "IDX")][type=="RefuelSessionEvent"]



plans[person_id == 5358084][!is.na(trip_id)]
###############


ggmap(oaklandMap) +
  theme_marain() +
  geom_sf(data = chargingEventsSf, aes(color = as.character(parkingZoneId)), inherit.aes = FALSE) +
  labs(color = "TAZs")


oakland_taz <- unique(out$taz1454)

charging_sessions$parkingTaz2 <- as.integer(charging_sessions$parkingTaz)
charging_sessions_oak <- charging_sessions[parkingTaz2 %in% oakland_taz]
charging_sessions_nonoak <- charging_sessions[!(parkingTaz2 %in% oakland_taz)]




ggmap(oaklandMap) +
  theme_marain() +
  geom_sf(data = out, aes(color = as.character(taz1454)), inherit.aes = FALSE) +
  labs(color = "TAZs")



ggplot(charging_sessions, aes(locationX, locationY)) + geom_point()


chargingEvents <- events[type == "ChargingPlugInEvent"][
  ,c("primaryFuelLevel", "vehicle", "secondaryFuelLevel", "parkingTaz", "chargingType", 
    "pricingModel", "parkingType", "price", 'locationX', "locationY")]

chargingEventsSf <- st_as_sf(chargingEvents, coords = c("locationX", "locationY"), crs = 4326, agr = "constant")

out <- st_intersection(chargingEventsSf, oaklandCbg)

source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oaklandMap <- ggmap::get_googlemap("oakland california", zoom = 13, maptype = "roadmap")

ggmap(oaklandMap) +
  theme_marain() +
  geom_sf(data = out, aes(color = as.character(taz1454)), inherit.aes = FALSE) +
  labs(color = "TAZs")


countyNames <- c('Alameda County','Contra Costa County','Marin County','Napa County','Santa Clara County','San Francisco County','San Mateo County','Sonoma County','Solano County')
counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
ggplot() +
  theme_marain() +
  geom_polygon(data=counties, mapping=aes(x=long,y=lat,group=group), fill="white", size=.2) +
  coord_map(projection = 'albers', lat0=39, lat1=45,xlim=c(-122.78,-121.86),ylim=c(37.37,38.17))+
  geom_point(dat=toplot,aes(x=x2,y=y2,size=mw,stroke=0.5,group=grp,color=mw),alpha=.3)+
  scale_color_gradientn(colours=c("darkgrey", "gold", "salmon", "orange", "red"), breaks=c(0.5,1,2,5)) +
  scale_size_continuous(range=c(0.5,35), breaks=c(0.5,1,2,5))+
  #scale_colour_continuous(breaks=c(999,5000,5001), values=c('darkgrey','orange','red'))+
  #scale_size_continuous(range=c(0.5,35), breaks=c(999,5000,5001))+
  labs(title="EV Charging Loads",colour='Load (MW)',size='Load (MW)',x="",y="")+
  theme(panel.background = element_rect(fill = "#d4e6f2"),
        legend.title = element_text(size = 20),
        legend.text = element_text(size = 20),
        axis.text.x = element_blank(), 
        axis.text.y = element_blank(), 
        axis.ticks.x = element_blank(),
        axis.ticks.y = element_blank())




## *******************************
activitySimDir <- normalizePath("~/Data/ACTIVITYSIM/activitysim-plans-base-2010")
#activitySimDir <- "~/Data/ACTIVITYSIM/plans-base-2010"
plans <- readCsv(pp(activitySimDir, "/plans.csv.gz"))
trips <- readCsv(pp(activitySimDir, "/trips.csv.gz"))
persons <- readCsv(pp(activitySimDir, "/persons.csv.gz"))
households <- readCsv(pp(activitySimDir, "/households.csv.gz"))
# blocks <- readCsv(pp(activitySimDir, "/blocks.csv", sep=""))

# events <- readCsv(pp(workDir, "2021Jul30-Oakland/15.events.csv.gz"))
# charging_sessions <- events[type == "RefuelSessionEvent"][
#   ,c("vehicle", "time", "vehicleType", "parkingTaz", "chargingType", 
#      "pricingModel", "parkingType", "locationX", "locationY", "parkingZoneId",
#      "price", "fuel", "duration")]
# 
# write.csv(
#   charging_sessions,
#   file = pp(workDir, "2021Jul30-Oakland/15.charging_events.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")

oakland_home <- plans[ActivityType == "Home"][,.(x=first(x),y=first(y)),by=.(person_id)]
oakland_homeSF <- st_as_sf(oakland_home, coords = c("x", "y"), crs = 4326, agr = "constant")
outOak <- st_intersection(oakland_homeSF, oaklandCbg)

nrow(outChargingSession[,.N,by=.(vehicle)]) # 34551
nrow(outOak) # 150463


write.csv(data.table::data.table(outChargingSession)[,-c("V1")], 
          file = pp(workDir, "2021Jul30-Oakland/0.charging_events_oakland_alameda.csv"),
          row.names=FALSE,
          quote=FALSE,
          na="0")


outChargingSession



######################### TEST 
# /Users/haitamlaarabi/Data/GEMINI/2021Aug17-SFBay/BASE0/events

activitySimDir <- "/Users/haitamlaarabi/Data/ACTIVITYSIM/activitysim-plans-base-2010-cut-718k-by-shapefile"
plans <- readCsv(pp(activitySimDir, "/plans.csv.gz"))






