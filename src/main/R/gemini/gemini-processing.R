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

workDir <- normalizePath("~/Data/GEMINI")
activitySimDir <- normalizePath("~/Data/ACTIVITYSIM")

source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oaklandMap <- ggmap::get_googlemap("oakland california", zoom = 13, maptype = "roadmap")
shpFile <- pp(workDir, "/shapefile/Oakland+Alameda+TAZ/Transportation_Analysis_Zones.shp")
oaklandCbg <- st_read(shpFile)

infra5aBase <- readCsv(pp(workDir, "/2022-04-28/_models/infrastructure/4a_output_2022_Apr_13_pubClust_withFees_aggregated.csv"))
infra5bBase <- readCsv(pp(workDir, "/2022-04-28/_models/infrastructure/4b_output_2022_Apr_13_pubClust_withFees.csv.gz"))
infra5bBase[startsWith(reservedFor,"household")]$reservedFor <- "Any"
write.csv(
  infra5bBase,
  file = pp(workDir, "/2022-04-28/_models/infrastructure/4b_output_2022_Apr_13_pubClust_withFees.csv"),
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

###
test1 <- readCsv(pp(workDir, "/test/0.events.test1.csv.gz"))
test1H <- readCsv(pp(workDir, "/test/0.events.test1H.csv.gz"))
test2 <- readCsv(pp(workDir, "/test/0.events.test2.csv.gz"))
test <- readCsv(pp(workDir, "/test/0.events.csv.gz"))
householdVehicles <- readCsv(pp(workDir, "/test/householdVehicles.csv"))
refuelEvents <- test[type=="RefuelSessionEvent"]
parkEvents <- test[type=="ParkingEvent"]
householdVehicles$vehicleId <- as.character(householdVehicles$vehicleId)
res <- parkEvents[householdVehicles, on=c(vehicle="vehicleId")]
res2 <- rbind(res[startsWith(i.vehicleType,"ev-")], res[startsWith(i.vehicleType,"phev-")])

events1 <- readCsv(pp(workDir, "/2022-04-27-Calibration/events/filtered.0.events.5b4.csv.gz"))
events2 <- readCsv(pp(workDir, "/2022-04-28/events/filtered.0.events.5bBase.csv.gz"))
test <- events2[type=="RefuelSessionEvent"][time-duration == 0]

infra <- readCsv(pp(workDir, "/2022-04/infrastructure/4a_output_2022_Apr_13_pubClust.csv"))
infra[, c("GEOM", "locationX", "locationY") := tstrsplit(geometry, " ", fixed=TRUE)]
infra <- infra[,-c("geometry", "GEOM")]
write.csv(
  infra,
  file = pp(workDir, "/2022-04/infrastructure/4a_output_2022_Apr_13_pubClust_XY.csv"),
  row.names=FALSE,
  quote=FALSE)



## temp
workdir <- "/Users/haitamlaarabi/Data/GEMINI/enroute-scenario3"
# default <- readCsv(pp(workdir,"/default.0.events.csv.gz"))
# default.ref <- default[type=="RefuelSessionEvent"]
# write.csv(
#   default.ref,
#   file = pp(workDir, "/default.0.events.pt.csv.gz"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
#
# enroute <- readCsv(pp(workdir,"/enroute.0.events.csv.gz"))
# enroute.ref <- enroute[type=="RefuelSessionEvent"]
# write.csv(
#   enroute.ref,
#   file = pp(workDir, "/enroute.0.events.pt.csv.gz"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")

default.ref <- readCsv(pp(workdir,"/default.0.events.pt.csv.gz"))
enroute.ref <- readCsv(pp(workdir,"/enroute.0.events.pt.csv.gz"))

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

##

# uncontrained_parking <- readCsv(pp(workDir, "/gemini_taz_parking_plugs_power_150kw_unlimited.csv"))
# uncontrained_parking[,.N,by=.(parkingType,pricingModel,chargingPointType,feeInCents)]
# parkingType pricingModel               chargingPointType feeInCents    N
# 1: Residential        Block                       NoCharger          0 1454
# 2: Residential        Block              HomeLevel1(1.8|AC)         50 1454
# 3: Residential        Block              HomeLevel2(7.2|AC)        200 1454
# 4:   Workplace        Block                       NoCharger          0 1454
# 5:   Workplace        Block           EVIWorkLevel2(7.2|AC)        200 1454
# 6:      Public        Block                       NoCharger          0 1454
# 7:      Public        Block EVIPublicLevel2(7.2|AC)(7.2|AC)        200 1454
# 8:      Public        Block         EVIPublicDCFast(150|DC)       7500 1454



# b_low_tech <- readCsv(pp(workDir, "/taz-parking-sparse-fast-limited-l2-150-lowtech-b.csv"))
# b_low_tech_sum <- b_low_tech[chargingType!="NoCharger",.N,by=.(parkingType,pricingModel,chargingType,feeInCents)]
# b_low_tech_sum[,.(feeInCents=mean(feeInCents)),by=.(parkingType,pricingModel,chargingType)]

# initInfra_1_5 <- readCsv(pp(workDir, "/init1.5.csv"))
# initInfra_1_5_updated <- initInfra_1_5[,c("subSpace", "pType", "chrgType", "household_id")]
# setnames(initInfra_1_5_updated, "chrgType", "chargingPointType")
# setnames(initInfra_1_5_updated, "pType", "parkingType")
# setnames(initInfra_1_5_updated, "subSpace", "taz")
# initInfra_1_5_updated$reservedFor <- "Any"
# initInfra_1_5_updated[!is.na(household_id)]$reservedFor <- paste("household(",initInfra_1_5_updated[!is.na(household_id)]$household_id,")",sep="")
# initInfra_1_5_updated <- initInfra_1_5_updated[,-c("household_id")]
# initInfra_1_5_updated <- initInfra_1_5_updated[,.(numStalls=.N),by=.(taz,parkingType,chargingPointType,reservedFor)]
# initInfra_1_5_updated$pricingModel <- "Block"
# initInfra_1_5_updated$feeInCents <- 0
# initInfra_1_5_updated[chargingPointType == "homelevel1(1.8|AC)"]$feeInCents <- 50
# initInfra_1_5_updated[chargingPointType == "homelevel2(7.2|AC)"]$feeInCents <- 200
# initInfra_1_5_updated[chargingPointType == "evipublicdcfast(150.0|DC)"]$feeInCents <- 7500
# initInfra_1_5_updated[chargingPointType == "evipubliclevel2(7.2|AC)"]$feeInCents <- 200
# initInfra_1_5_updated[chargingPointType == "eviworklevel2(7.2|AC)"]$feeInCents <- 200
# initInfra_1_5_updated[,`:=`(parkingZoneId=paste("AO-PEV",taz,1:.N,sep="-")),by=.(taz)]
# ####
# alameda_oakland_tazs <- unique(initInfra_1_5_updated$taz)
# no_charger_or_non_AlamedaOakland_constrained <- sfbay_contrained_parking[
#   chargingPointType == "NoCharger" | !(taz %in% alameda_oakland_tazs)][
#     ,`:=`(parkingZoneId=paste("X-PEV",taz,1:.N,sep="-"),
#           locationX="",locationY=""),by=.(taz)
#   ]
# initInfra_1_5_updated_constrained_non_AlamedaOakland <- rbind(initInfra_1_5_updated, no_charger_or_non_AlamedaOakland_constrained)
# write.csv(
#   initInfra_1_5_updated_constrained_non_AlamedaOakland,
#   file = pp(workDir, "/gemini-base-scenario-2-parking-initInfra15-and-constrained-nonAO.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="")
# ##
#
# uncontrained_rh_parking <- readCsv(pp(workDir, "/gemini_depot_parking_power_150kw.csv"))
# uncontrained_rh_parking[,`:=`(parkingZoneId=paste("X-REV",taz,1:.N,sep="-")),by=.(taz)]
# uncontrained_rh_parking[taz %in% alameda_oakland_tazs,`:=`(parkingZoneId=paste("AO-PEV",taz,1:.N,sep="-")),by=.(taz)]
# write.csv(
#   uncontrained_rh_parking,
#   file = pp(workDir, "/gemini-base-scenario-2-depot-constrained.csv"),
#   row.names=FALSE,
#   quote=FALSE,
#   na="")
#
#
# initInfra_1_5_updated_constrained_non_AlamedaOakland[startsWith(reservedFor, "household")]
#
# initInfra_1_5[household_id == 1800619]


###########
sfbay_contrained_parking <- readCsv(
  pp(workDir, "/taz-parking-sparse-fast-limited-l2-150-lowtech-b.csv")
)
#sfbay_contrained_parking[chargingType!="NoCharger",.(fee=max(feeInCents)),by=.(parkingType,chargingType)]
#sfbay_contrained_parking[chargingType!="NoCharger",.N,by=.(parkingType,chargingType)]
sfbay_contrained_parking$chargingPointType <- "NoCharger"
sfbay_contrained_parking[chargingType=="WorkLevel2(7.2|AC)"&parkingType=="Public"]$chargingPointType <- "publiclevel2(7.2|AC)"
sfbay_contrained_parking[chargingType=="WorkLevel2(7.2|AC)"&parkingType=="Workplace"]$chargingPointType <- "worklevel2(7.2|AC)"
sfbay_contrained_parking[chargingType=="Custom(150.0|DC)"]$chargingPointType <- "publicfc(150.0|DC)"
sfbay_contrained_parking[chargingType=="HomeLevel2(7.2|AC)"]$chargingPointType <- "homelevel2(7.2|AC)"
sfbay_contrained_parking[chargingType=="HomeLevel1(1.8|AC)"]$chargingPointType <- "homelevel1(1.8|AC)"
sfbay_contrained_parking <- sfbay_contrained_parking[,-c("chargingType")]
setnames(sfbay_contrained_parking, "ReservedFor", "reservedFor")
#sfbay_contrained_parking[chargingPointType!="NoCharger",.N,by=.(parkingType,chargingPointType)]

initInfra_1_5 <- readCsv(pp(workDir, "/init1.6_2021_Oct_06_wgs84.csv"))
initInfra_1_5_updated <- initInfra_1_5[,c("subSpace", "pType", "chrgType", "field_1", "household_id", "X", "Y")]
setnames(initInfra_1_5_updated, "chrgType", "chargingPointType")
setnames(initInfra_1_5_updated, "pType", "parkingType")
setnames(initInfra_1_5_updated, "subSpace", "taz")
setnames(initInfra_1_5_updated, "X", "locationX")
setnames(initInfra_1_5_updated, "Y", "locationY")
initInfra_1_5_updated$reservedFor <- "Any"
initInfra_1_5_updated[!is.na(household_id)]$reservedFor <- paste("household(",initInfra_1_5_updated[!is.na(household_id)]$household_id,")",sep="")
initInfra_1_5_updated <- initInfra_1_5_updated[,-c("household_id", "field_1")]
initInfra_1_5_updated$pricingModel <- "Block"
initInfra_1_5_updated$feeInCents <- 0
setFees <- function(DF, DF_FEE) {
  convertToVectorOfFee <- function(DF_TEMP, SKIP_REP) {
    if(SKIP_REP == TRUE) {
      return(DF_TEMP$feeInCents)
    } else {
      res <- c()
      for (i in 1:dim(DF_TEMP)[1]) {
        feeInCents <- DF_TEMP[i]$feeInCents
        numStalls <- DF_TEMP[i]$numStalls
        res <- c(res, rep(feeInCents, numStalls))
      }
      return(res)
    }
  }
  set.seed(5)
  for (i in 1:dim(DF)[1]) {
    tazArg <- DF[i]$taz
    parkingTypeArg <- DF[i]$parkingType
    chargingTypeArg <- DF[i]$chargingPointType
    if(chargingTypeArg=="publicxfc(250.0|DC)") {
      chargingTypeArg <- "publicfc(150.0|DC)"
    }
    filtered <- DF_FEE[taz==tazArg&parkingType==parkingTypeArg&chargingPointType==chargingTypeArg]
    SKIP_REP <- FALSE
    if(nrow(filtered) == 0) {
      filtered <- DF_FEE[parkingType==parkingTypeArg&chargingPointType==chargingTypeArg]
      SKIP_REP <- TRUE
    }
    if(nrow(filtered) > 0) {
      vectFee <- convertToVectorOfFee(filtered, SKIP_REP)
      fee <- vectFee[sample(length(vectFee), 1)][1]
      if(DF[i]$chargingPointType=="publicxfc(250.0|DC)") {
        DF[i]$feeInCents <- fee*1.6
      } else {
        DF[i]$feeInCents <- fee
      }
    }
  }
  return(DF)
}
initInfra_1_5_updated$feeInCents <- 0
initInfra_1_5_updated <- setFees(initInfra_1_5_updated, sfbay_contrained_parking)
#initInfra_1_5_updated[chargingPointType!="NoCharger",.(fee=mean(feeInCents)),by=.(parkingType,chargingPointType)]
#sfbay_contrained_parking[chargingPointType!="NoCharger",.(fee=sum(feeInCents*numStalls)/sum(numStalls)),by=.(parkingType,chargingPointType)]
initInfra_1_5_updated[,`:=`(parkingZoneId=paste("AO-PEV",taz,1:.N,sep="-")),]
initInfra_1_5_updated$numStalls <- 1
write.csv(
  initInfra_1_5_updated,
  file = pp(workDir, "/init1.6_2021_Oct_06_wgs84_updated.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="")

alameda_oakland_tazs <- unique(initInfra_1_5_updated$taz)
no_charger_or_non_AlamedaOakland_constrained <- sfbay_contrained_parking[
  chargingPointType == "NoCharger" | !(taz %in% alameda_oakland_tazs)][
    ,`:=`(parkingZoneId=paste("X-PEV",taz,1:.N,sep="-"),
          locationX="",
          locationY=""),by=.(taz)
  ]
initInfra_1_5_updated_constrained_non_AlamedaOakland <- rbind(initInfra_1_5_updated, no_charger_or_non_AlamedaOakland_constrained)
write.csv(
  initInfra_1_5_updated_constrained_non_AlamedaOakland,
  file = pp(workDir, "/gemini-base-scenario-3-parking-charging-infra16.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="")


infra16 <- readCsv(pp(workDir, "/gemini-base-scenario-3-parking-charging-infra16.csv"))
infra16_charging <- infra16[chargingPointType!="NoCharger"]
write.csv(
  infra16_charging,
  file = pp(workDir, "/gemini-base-scenario-3-charging-with-household-infra16.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="")
infra16_charging[startsWith(reservedFor, "household")]$reservedFor <- "Any"
write.csv(
  infra16_charging,
  file = pp(workDir, "/gemini-base-scenario-3-charging-no-household-infra16.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="")


infra16_parking <- infra16[chargingPointType=="NoCharger"]
write.csv(
  infra16_parking,
  file = pp(workDir, "/gemini-base-scenario-3-parking-infra16.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="")


#####
chargingBehaviorFunc <- function(DT) {
  rseSum <- DT[,.(fuel=sum(fuel)),by=.(parkingType,chargingPointType)]
  rseSum[,fuelShare:=fuel/sum(fuel)]
  #dcfc <- rseSum[chargingPointType=="publicfc(150.0|DC)"]$fuelShare + rseSum[chargingPointType=="publicxfc(250.0|DC)"]$fuelShare
  #publicL2 <- rseSum[chargingPointType=="publiclevel2(7.2|AC)"]$fuelShare
  #work <- rseSum[chargingPointType=="worklevel2(7.2|AC)"]$fuelShare
  #home <- rseSum[chargingPointType=="homelevel1(1.8|AC)"]$fuelShare + rseSum[chargingPointType=="homelevel2(7.2|AC)"]$fuelShare
  print("************************")
  print(rseSum)
  #print(pp("DCFC: ",dcfc," - ",))
  #print(pp("PublicL2: ",publicL2))
  #print(pp("Work: ",work))
  #print(pp("Home: ",home))
}

events100_SC3 <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC3.csv.gz"
rse100_SC3_a <- readCsv(pp(workDir, events100_SC3))
rse100_SC3_c <- rse100_SC3_a[type=='RefuelSessionEvent']
rse100_SC3_d <- rse100_SC3_a[type=='ChargingPlugInEvent']

events100_SC2 <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC2.csv.gz"
rse100_SC2_a <- readCsv(pp(workDir, events100_SC2))
rse100_SC2_c <- rse100_SC2_a[type=='RefuelSessionEvent']
rse100_SC2_d <- rse100_SC2_a[type=='ChargingPlugInEvent']

vehicles <- se100_SC3_c[startsWith(parkingZoneId,"AO-")]$vehicle
nrow(se100_SC3_c[startsWith(parkingZoneId,"AO-")])
nrow(rse100_SC3_d[vehicle%in%vehicles])


ev1 <- rse100_SC3_a[type %in% c("RefuelSessionEvent")][order(time),`:=`(IDX = 1:.N),by=vehicle]
ev2 <- rse100_SC3_a[type %in% c("ChargingPlugInEvent")][,c("vehicle", "time")][order(time),`:=`(IDX = 1:.N),by=vehicle]
ev <- ev1[ev2, on=c("vehicle", "IDX")]

ev1 <- rse100_SC2_a[type %in% c("RefuelSessionEvent")][order(time),`:=`(IDX = 1:.N),by=vehicle]
ev2 <- rse100_SC2_a[type %in% c("ChargingPlugInEvent")][,c("vehicle", "time")][order(time),`:=`(IDX = 1:.N),by=vehicle]
ev <- ev1[ev2, on=c("vehicle", "IDX")]


events010 <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC2-010.csv.gz"
rse010 <- readCsv(pp(workDir, events010))[type=='RefuelSessionEvent']
events025 <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC2-025.csv.gz"
rse025 <- readCsv(pp(workDir, events025))[type=='RefuelSessionEvent']
events050 <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC2-050.csv.gz"
rse050 <- readCsv(pp(workDir, events050))[type=='RefuelSessionEvent']

# 20.03*sum(rse100$fuel)/sum(rse010$fuel)
# 10.01*sum(rse100$fuel)/sum(rse025$fuel)
# 5.8*sum(rse100$fuel)/sum(rse050$fuel)

charging_coef <- data.table(
  actType=c("Home", "Work", "Charge", "Wherever", "Init"),
  coef=c(0, 0, 0, 0, 0)
)

sum(rse100_SC3_a[type %in% c("RefuelSessionEvent")]$fuel)/sum(rse100_SC2_a[type %in% c("RefuelSessionEvent")]$fuel)
sum(rse100_SC3_a[type %in% c("RefuelSessionEvent")&!startsWith(parkingZoneId,"X-")&chargingPointType%in%homelevel]$fuel)/sum(rse100_SC2_a[type %in% c("RefuelSessionEvent")&!startsWith(parkingZoneId,"X-")&chargingPointType%in%homelevel]$fuel)


charging100 <- rse100_SC2[,.(fuel100=mean(fuel)),by=.(actType)]
charging010 <- rse010[,.(fuel010=mean(fuel)),by=.(actType)][charging_coef,on=c("actType")][charging100,on=c("actType")]
charging025 <- rse025[,.(fuel025=mean(fuel)),by=.(actType)][charging_coef,on=c("actType")][charging100,on=c("actType")]
charging050 <- rse050[,.(fuel050=mean(fuel)),by=.(actType)][charging_coef,on=c("actType")][charging100,on=c("actType")]

charging010$coef <- c(10.54, 10.06, 10.09, 6.42)
charging010 <- charging010[,fuel_100_010:=fuel100/fuel010][,fuel_100_010_W:=coef*fuel_100_010]

charging025$coef <- c(3.99, 4.02, 3.98, 2.52)
charging025 <- charging025[,fuel_100_025:=fuel100/fuel025][,fuel_100_025_W:=coef*fuel_100_025]

charging050$coef <- c(2, 2, 2, 2)
charging050 <- charging050[,fuel_100_050:=fuel100/fuel050][,fuel_100_050_W:=coef*fuel_100_050]

# charging <- rse100[,.(fuel100=sum(fuel)),by=.(parkingType, chargingPointType)]

# charging_100_010 <- data.table(
#   chargingPointType=c("homelevel1(1.8|AC)", "homelevel2(7.2|AC)",
#                       "worklevel2(7.2|AC)","publiclevel2(7.2|AC)",
#                       "publicfc(150.0|DC)", "publicxfc(250.0|DC)"),
#   coef_100_010=c(8.02,6.87,9.00,6.41,8.17,10.33))
# charging010 <- rse010[,.(fuel010=sum(fuel)),by=.(parkingType,chargingPointType)][
#   charging,on=c("parkingType","chargingPointType")][
#     charging_100_010,on=c("chargingPointType")][
#       ,fuel_100_010:=fuel100/fuel010][
#         ,fuel_100_010_W:=coef_100_010*fuel_100_010]
#
#
# charging_100_025 <- data.table(
#   chargingPointType=c("homelevel1(1.8|AC)", "homelevel2(7.2|AC)",
#                       "worklevel2(7.2|AC)","publiclevel2(7.2|AC)",
#                       "publicfc(150.0|DC)", "publicxfc(250.0|DC)"),
#   coef_100_025=c(2.95,2.56,3.69,2.65,3.18,4.53))
# charging025 <- rse025[,.(fuel025=sum(fuel)),by=.(parkingType,chargingPointType)][
#   charging,on=c("parkingType","chargingPointType")][
#     charging_100_025,on=c("chargingPointType")][
#       ,fuel_100_025:=fuel100/fuel025][
#         ,fuel_100_025_W:=coef_100_025*fuel_100_025]
#
#
# charging_100_050 <- data.table(
#   chargingPointType=c("homelevel1(1.8|AC)", "homelevel2(7.2|AC)",
#                       "worklevel2(7.2|AC)","publiclevel2(7.2|AC)",
#                       "publicfc(150.0|DC)", "publicxfc(250.0|DC)"),
#   coef_100_050=c(1.33,1.20,1.88,1.35,1.67,2.28))
# charging050 <- rse050[,.(fuel050=sum(fuel)),by=.(parkingType,chargingPointType)][
#   charging,on=c("parkingType","chargingPointType")][
#     charging_100_050,on=c("chargingPointType")][
#       ,fuel_100_050:=fuel100/fuel050][
#         ,fuel_100_050_W:=coef_100_050*fuel_100_050]
#
#
# charging <- charging[charging010, on=c("parkingType","chargingPointType")]
# charging <- charging[charging025, on=c("parkingType","chargingPointType")]
# charging <- charging[charging050, on=c("parkingType","chargingPointType")]
#
# publiclevel2(7.2|AC)
# publicfc(150.0|DC)
# worklevel2(7.2|AC)
# homelevel2(7.2|AC)
# homelevel1(1.8|AC)
# publicxfc(250.0|DC)
#c(2.45, 1119.77, 739.21, 8.05, 2.35, 171.38)
#c(3.82, 141.68, 112.27, 9.02, 5.64, 64.84)
#c(10.0,10.0,10.0,10.0,10.0,10.0)
charging_0_010 <- data.table(
  chargingPointType=c("homelevel1(1.8|AC)", "homelevel2(7.2|AC)",
                      "worklevel2(7.2|AC)","publiclevel2(7.2|AC)",
                      "publicfc(150.0|DC)", "publicxfc(250.0|DC)"),
  fuel0_010_coef=c(8.02,6.87,9.00,6.41,8.17,10.33))
charging <- charging[charging_0_010,on=c("chargingPointType")][,fuel0_010:=fuel0/fuel001][,fuel0_010_t:=fuel0_010_coef*fuel0_010]
#charging[,fuelShare0_001:=fuelShare0/fuelShare001]
#c(1.74, 21.39, 21.28, 3.55, 2.54, 13.11)
#c(1.10, 101.73, 91.85, 2.79, 1.30, 32.04)
#c(1.10, 101.73, 91.85, 2.79, 1.30, 32.04)
#c(4.0,4.0,4.0,4.0,4.0,4.0)
charging[,fuel0_025_coef:=c(4.0,4.0,4.0,4.0,4.0,4.0)]
charging[,fuel0_025:=fuel0/fuel010]
charging[,fuel0_025_t:=fuel0_025_coef*fuel0_025]
#charging[,fuelShare0_010:=fuelShare0/fuelShare010]
#c(1.0, 14.34, 14.28, 1.21, 0.70, 5.93)
#c(1.07, 5.58, 5.49, 1.87, 1.42, 3.65)
#c(2.0, 2.0, 2.0, 2.0, 2.0, 2.0)
charging[,fuel0_050_coef:=c(2.0, 2.0, 2.0, 2.0, 2.0, 2.0)]
charging[,fuel0_050:=fuel0/fuel050]
charging[,fuel0_050_t:=fuel0_050_coef*fuel0_050]
#charging[,fuelShare0_050:=fuelShare0/fuelShare050]
#c(1.0,290.55,325.98,2.64,1.0,53.32)
#chargingBis <- charging[,c("parkingType","chargingPointType","fuel0_025")]
chargingBis <- charging[
  ,c("parkingType","chargingPointType","fuel0_010", "fuel0_025","fuel0_050")]
chargingBisT <- charging[
  ,c("parkingType","chargingPointType","fuel0_010_t", "fuel0_025_t","fuel0_050_t")]

gather(chargingBis, scenario, fuelDiff, fuel0_001:fuel0_050) %>%
  ggplot(aes(scenario, fuelDiff, fill=chargingPointType)) +
  geom_bar(stat='identity',position='dodge')


chargingBis$rate <- 4.0*((chargingBis$fuel0_010/chargingBis$fuel0_050)/5.0)

###

testFile <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC2.csv.gz"
test <- readCsv(pp(workDir, testFile))
test2 <- test[type=='RefuelSessionEvent' & time >= 41400 & time <= 45000]
person2 <- unique(test2$person)
test3 <- test[person%in%person2][actType!=""]
test4 <- test3[time >= 41400 & time <= 45000]
test4[actType!="",.N,by=.(actType)][order(N)]

test3All <- test[type=="actend"][time<=16*3600&time>=8*3600][sample(.N,234768)]

test3All$actType2 <- "discr"
test3All[actType=="work"]$actType2 <- "work"
test3All[actType=="Work"]$actType2 <- "work"
test3All[actType=="atwork"]$actType2 <- "work"
test3All[actType=="Home"]$actType2 <- "home"
test3All$time2 <- test3All$time%%(24*3600)
#time<=14*3600&time>=10*3600,
test3All[,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(time),"15 min")), actType2)] %>%
  ggplot(aes(timeBin, N, colour=actType2)) +
  geom_line() +
  scale_x_datetime("time",
                   breaks=scales::date_breaks("2 hour"),
                   labels=scales::date_format("%H", tz = dateTZ)) +
  scale_y_continuous(breaks = scales::pretty_breaks()) +
  theme_classic() +
  theme(axis.text.x = element_text(angle = 90, hjust = 1))

####

looFile <- "/activitysim-plans-base-2010-cut-718k-by-shapefile/plans.csv.gz"
looTest <- readCsv(pp(activitySimDir, looFile))
#looTest2 <- looTest[ActivityElement=="activity"&person_id%in%person2]
looTest2 <- looTest[ActivityElement=="activity"]
looTest2$actType2 <- "discr"
looTest2[ActivityType=="work"]$actType2 <- "work"
looTest2[ActivityType=="Work"]$actType2 <- "work"
looTest2[ActivityType=="atwork"]$actType2 <- "work"
looTest2[ActivityType=="Home"]$actType2 <- "home"
looTest2[departure_time<=16&departure_time>=8,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(departure_time*3600),"1 hour")), actType2)] %>%
  ggplot(aes(timeBin, N, colour=actType2)) +
  geom_line() +
  scale_x_datetime("time",
                   breaks=scales::date_breaks("2 hour"),
                   labels=scales::date_format("%H", tz = dateTZ)) +
  scale_y_continuous(breaks = scales::pretty_breaks()) +
  theme_classic() +
  theme(axis.text.x = element_text(angle = 90, hjust = 1))
###

fooFile <- "/2021Aug22-Oakland/beamLog.out-choiceset.txt"

##

sc2 <- readCsv(pp(workDir, "/gemini-base-scenario-2-parking-charging-infra16.csv"))
sc3 <- readCsv(pp(workDir, "/gemini-base-scenario-3-parking-charging-infra16.csv"))

sc2Stalls <- sc2[startsWith(reservedFor, "household")]$parkingZoneId
sc3Stalls <- sc3[startsWith(reservedFor, "household")]$parkingZoneId
sum(sc2[startsWith(reservedFor, "household")]$numStalls)
sum(sc3[startsWith(reservedFor, "household")]$numStalls)

a <- sum(rse100_3[startsWith(parkingZoneId, "AO")]$fuel)
b <- sum(rse100[startsWith(parkingZoneId, "AO")]$fuel)

b <- rse100_3[startsWith(parkingZoneId, "AO"),.(fuel3=mean(fuel)),by=.(chargingPointType)]
a <- rse100[startsWith(parkingZoneId, "AO"),.(fuel2=mean(fuel)),by=.(chargingPointType)]



###

events <- readCsv(pp(workDir, "/2021Oct29/BATCH1/events/filtered.0.events.SC4Bis5.csv.gz"))
events.sim <- readCsv(pp(workDir, "/2021Oct29/BATCH1/sim/events.sim.SC4Bis5.csv.gz"))
#temp <- readCsv(pp(workDir, "/2021Oct29/BATCH1/sim/events.sim.SC4Bis2.csv.gz"))

chargingEvents <- events.sim[,-c("type", "IDX")]
nrow(chargingEvents[duration==0])
nrow(chargingEvents[duration>0])

chargingEvents[duration<1800&duration>0] %>% ggplot(aes(duration)) +
  theme_classic() +
  geom_histogram(bins = 30)

test <- chargingEvents[duration==0]
test$kindOfVehicle <- "real"
test[startsWith(vehicle,"VirtualCar")]$kindOfVehicle <- "virtual"


write.csv(
  chargingEvents[duration>0],
  file = pp(workDir, "/2021Oct29/BATCH1/chargingEventsFullBayArea.csv.gz"),
  row.names=FALSE,
  quote=FALSE,
  na="0")



## calibration
summarizingCharging <- function(DATA) {
  refSession <- DATA[type=="RefuelSessionEvent"]
  refSession$loadType <- ""
  refSession[chargingPointType == "homelevel1(1.8|AC)"]$loadType <- "Home L1"
  refSession[chargingPointType == "homelevel2(7.2|AC)"]$loadType <- "Home L2"
  refSession[chargingPointType == "worklevel2(7.2|AC)"]$loadType <- "Work L2"
  refSession[chargingPointType == "publiclevel2(7.2|AC)"]$loadType <- "Public L2"
  refSession[grepl("fc", chargingPointType)]$loadType <- "Public L3"
  chgSummary <- refSession[,.(sumFuel=sum(fuel)),by=.(loadType)]
  chgSummaryTotFuel <- sum(chgSummary$sumFuel)
  chgSummary[,shareFuel:=sumFuel/chgSummaryTotFuel][,-c("sumFuel")][order(loadType)]
  print(chgSummary[,-c("sumFuel")][order(loadType)])
  home1 <- chgSummary[loadType == "Home L1"]$shareFuel
  home2 <- chgSummary[loadType == "Home L2"]$shareFuel
  print("Home L1+L2")
  home1 + home2
}

summarizingCharging2 <- function(DATA) {
  chgSummary <- DATA[type=="RefuelSessionEvent"][,.(sumFuel=sum(fuel)),by=.(actType)]
  chgSummaryTotFuel <- sum(chgSummary$sumFuel)
  chgSummary[,shareFuel:=sumFuel/chgSummaryTotFuel][,-c("sumFuel")][order(actType)]
}

events00 <- readCsv(pp(workDir, "/2022Feb/BATCH1/events/filtered.0.events.SC4b.csv.gz"))
events01 <- readCsv(pp(workDir, "/2022Feb/BATCH1/events/filtered.0.events.SC4b2.csv.gz"))

events1 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-1.csv.gz"))
events2 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-2.csv.gz"))
events3 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-3.csv.gz"))
events4 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-4.csv.gz"))
events5 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-5.csv.gz"))
events6 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-6.csv.gz"))
events7 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-7.csv.gz"))
events8 <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-8.csv.gz"))

# ev <- events1[startsWith(vehicleType, "ev-")]
# phev <- events1[startsWith(vehicleType, "phev-")]
# virtual <- events1[startsWith(vehicleType, "VirtualCarType")]
#
# allEv <- rbind(ev,phev)
# allEVSummary <- allEv[,.(.N),by=.(vehicle,vehicleType)]
# allEVSummary$vehType <- "Car"
# allEVSummary[grepl("Midsize",vehicleType)]$vehType <- "Midsize"
# allEVSummary[grepl("SUV",vehicleType)]$vehType <- "SUV"
# allEVSummary[grepl("Pickup",vehicleType)]$vehType <- "Pickup"
# allEVSummary[,.N,by=.(vehType)]
#
#
# n_ev <- length(unique(ev$vehicle))
# n_phev <- length(unique(phev$vehicle))
# n_virtual <- length(unique(virtual$vehicle))
# n_ev_virtual <- n_virtual*(n_ev/(n_ev+n_phev))
# n_phev_virtual <- n_virtual*(n_phev/(n_ev+n_phev))
#
# tot <- n_ev + n_phev + n_ev_virtual + n_phev_virtual

# home_l1 <- c(214111.2,211923.08,208621.38,202296.8,194829.95,187391.03,179245.57,171491.27,164053.89,156328.68,149317.28,142827.17,136334.76,129988.95,123245.08,117163.17,110797.83,104364.94,98190.02,92319.37,86389.21,80431.88,74753.37,69292.44,62771.91,57877.63,52755.43,48026.31,42823.35,38926.68,35399.17,33372.94,32068.76,31352.66,30194.87,28925.32,27057.94,26295.52,25438.38,25708.78,26050.95,27227.11,27994.12,29514.74,32059.96,34931.46,37853.67,41214.31,44844.4,49689.12,54051.58,56591.05,58572.11,61640.34,64870.28,67340.67,70304.79,75464.69,82198.98,87824.1,96141.41,106715.73,117904.53,126863.88,136510.25,146884.4,157953.02,167726.08,179445.74,194061.4,210797.06,224026.44,236691.85,247128.19,255858.86,260320.45,263116.93,265472.88,268295.78,270321.62,271914.39,272345.16,271250.72,272341.52,271938.5,273983.09,274960.99,273487.06,271519.02,268411.56,265309.26,260089.08,254698.2,248714.27,241775.97,234571.86)
# home_l2 <- c(163140.29,144023.61,125243.75,104454.89,98585.96,83711.95,76033.63,66311.86,56915.23,49768.92,44792.73,37462.7,33046.28,28099.37,22954.78,19483.53,15918.31,13051.22,11216.17,8809.13,6872.47,6118.66,5179.04,6088.62,5961.16,6490.87,7593.55,8055.51,9037.62,9724.45,12430.41,15092.36,20329.96,22542.76,23192.08,21461.53,21462.48,22072.19,24639.03,27293.13,29829.93,32118.33,39065.04,43416.6,48766.72,60068.05,65967.97,72970.38,82464.03,97277.38,105904.7,105871.21,105859.73,112139.33,118064.71,119554.33,126458.18,134333.42,157911.3,174437.79,201619.36,228802.07,253878.37,266873.71,296407.97,325078.39,339972.5,353857.51,385748.1,422134.16,463759.92,488407.37,516707.11,520134.54,526323.62,522026.8,520062.77,503784.68,483819.28,466238.16,456407.69,439618.65,430171.49,419233.96,409436.02,400816.55,383956.12,363607.02,347688.71,327030.36,306217.58,280592.42,256449.04,234875.95,214052.64,191748.7)
# work_l1 <- c(1562.72,1507.23,1485.79,1452.3,1270.89,1158.17,1125.25,1082.39,957.04,910.92,830.16,768.73,638.03,595.54,575.83,584.06,619.46,629.61,767.97,935.22,1281.22,1705.68,2335.86,2961.44,4114.64,4720.51,5862.99,7249.08,9493.85,10814.87,13062.7,15345.55,18619.88,19822.45,20812.02,21449.09,22778.72,23018.32,23094.1,23204.52,23374.46,23130.46,22902.35,22569.56,22279.06,21909.91,21265.37,20302.78,19036.87,18252.83,17586.1,17087.01,16860.04,16603.8,16153.51,15625.9,14838.22,14334.92,13848.08,13272.63,12127.09,11452.13,10663.69,10268.7,9478.92,8735.07,7993.7,7205.07,6184.3,5529.62,4812.18,4533.16,4036.37,3692.48,3513.35,3422.07,3267.83,3036.08,2833.8,2746.54,2622.34,2443.98,2240.36,2094.16,2047.65,2036.17,2054.93,2105.26,1891.69,1810.74,1769.79,1712.57,1686.92,1681.37,1628.94,1677.35)
# work_l2 <- c(2652.38,2488.76,2540.62,2572.39,2072.72,1872.36,1807.87,1945.27,1776.29,1547.8,1435.27,1562.15,1498.42,1460.53,2251.85,3499,5391.65,6120.19,6930.83,9421.13,15780.72,18806.46,24901.78,32720.76,48371.92,57512.69,74029.8,90552.08,125095.25,143729.09,173419.71,198975.58,246980.46,251954.92,256120.84,250745.85,254545.87,240578.38,223418.84,207430.87,190271.9,167443.04,150213.27,133096.22,119458.84,107286.2,95935.88,85443.66,77644.96,71057.83,66784.16,66993.52,66857.46,62364.67,61714.21,59384.28,56212.14,52570.95,49901.92,48116.44,45140.64,41447.78,42206.57,39041.89,37433.23,34350.84,31920.26,30529.58,28071.62,26228.93,24383.74,22007.31,20483.44,17821.87,17092.18,17393.01,16069.11,14293.97,12657.19,11710.86,10525.33,8932.18,8141.25,7892.47,7812.86,6883.76,6105.46,5509.72,5533.07,4846.82,4352.89,4475.37,4098.18,3458.24,3196.06,2820.21)
# public_l2 <- c(19788.76,18253.6,15948.36,14610.69,13154.36,12062.03,10948.83,10387.54,9408.3,8252.05,6699.28,5991.59,5558.33,5303.43,5069,4458.53,4354.81,4287.25,3674.87,3940.49,5068.23,6015.32,7886.53,9011.21,13842.34,16395.02,18872.87,21881.2,25059.47,27813.08,32476.95,37535.23,47378.52,51027.56,55825.96,57967.57,65746.17,70316.27,73245.57,72648.68,76039.95,75117.16,76287.96,76544.59,76267.29,74199.54,75421.25,76531.96,79423.55,79431.21,78182.71,75911.15,76946.27,75698.35,75548.89,75372.07,75104.91,71943.68,70442.19,68094.09,72936.12,72580.56,70357.99,73058.22,73292.64,71539.89,69061.84,70978.03,72952.01,72969.42,76923.5,81983.69,87958.83,90399.75,90921.8,94572.56,97035.49,93019.79,88844.68,85019.96,78802.37,73560.37,64138.86,58119.14,50942.78,46139.02,42267.61,36899.5,33431.12,30559.24,29203,29484.51,26575.88,24058.79,23409.67,21778.24)
# public_l3 <- c(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,392.5,725.1,185.05,0,0.19,947.28,1440.06,4366.86,5009.1,4164.97,2661.76,4667.31,3887.1,8270.8,7615.17,14079.25,14335.69,11754.3,10793.05,13989.31,11593.17,13399.51,15847.89,17059.26,15505.72,14141.45,14448.21,16772.97,13977.83,16665.23,17113.04,16727.62,18224.13,21393.59,17138.68,16346.03,13321.24,13832.77,17703.98,17284.69,14626,18584.28,20115.24,16532.61,13048.34,11299.61,10443.42,9273.77,9134.84,12408.02,16232.54,17234.55,14942.52,9471.07,11043.17,13640.25,16696.61,14306.41,11058.68,8682.63,5775.34,3623.39,3078.95,2567.03,1351.45,392.31,392.31,392.31,418.33,784.62,624.82,392.31,392.31,279.59,0)
#
# tot <- sum(home_l1) + sum(home_l2) + sum(work_l1) + sum(work_l2) + sum(public_l2) + sum(public_l3)
# sum(home_l1)/tot
# sum(home_l2)/tot
# (sum(home_l1)+sum(home_l2))/tot
# (sum(work_l1)+sum(work_l2))/tot
# sum(public_l2)/tot
# sum(public_l3)/tot

# LoadType,Share,period
# Home L1,33.8%,weekday
# Home L2,43.7%,weekday
# Work L1+L2,9.2%,weekday
# Public L2,11.4%,weekday
# Public L3,1.8%,weekday
# Home L1+L2,77.5,weekday

summarizingCharging(events00)
summarizingCharging(events01)

summarizingCharging(events1)
summarizingCharging(events2)
summarizingCharging(events3)
summarizingCharging(events4)
summarizingCharging(events5)
summarizingCharging(events6)
summarizingCharging(events7)
summarizingCharging(events8)

ref2 <- events2[(type=="RefuelSessionEvent") & (time < 5*3600)]
summarizingCharging2(ref2)
events2[type=="RefuelSessionEvent",.N,by=.(time2=time/60.0)] %>% ggplot(aes(time2,N)) + geom_line()
stationsUnlimited <- readCsv(pp(workDir, "/2022Mars-Calibration/gemini_taz_unlimited_charging_point.csv"))
stationsUnlimitedBis <- stationsUnlimited[,.(stalls=sum(numStalls),fees=mean(feeInCents)),by=.(chargingPointType)]
stationsUnlimitedBis$shareStalls<- stationsUnlimitedBis$stalls / sum(stationsUnlimitedBis$stalls)

stationsTemp <- readCsv(pp(workDir, "/2022Mars-Calibration/gemini-base-scenario-3-charging-no-household-infra16.csv"))
stationsTempBis <- stationsTemp[,.(stalls=sum(numStalls),fees=mean(feeInCents)),by=.(chargingPointType)]
stationsTempBis$shareStalls <- stationsTempBis$stalls / sum(stationsTempBis$stalls)

stationsAgg <- readCsv(pp(workDir, "/2022Mars-Calibration/init1-7_2022_Feb_03_wgs84_withFees_aggregated.csv"))
stationsAggBis <- stationsAgg[,.(stalls=sum(numStalls),fees=mean(feeInCents)),by=.(chargingPointType)]
stationsAggBis$shareStalls<- stationsAggBis$stalls / sum(stationsAggBis$stalls)

stations <- readCsv(pp(workDir, "/2022Mars-Calibration/init1-7_2022_Feb_03.csv"))
stationsBis <- stations[,.(stalls=.N),by=.(chrgType)]
stationsBis$shareStalls<- stationsBis$stalls / sum(stationsBis$stalls)

mnl <- readCsv(pp(workDir, "/2022Mars-Calibration/mnl-beamLog.csv"))
mnl_search <- readCsv(pp(workDir, "/2022Mars-Calibration/mnl-search-beamLog.csv"))
mnl_param <- readCsv(pp(workDir, "/2022Mars-Calibration/mnl-param-beamLog.csv"))
sampled <- mnl[mnlStatus=="sampled"]
chosen <- mnl[mnlStatus=="chosen"]

mnl_param$result <- mnl_param$RangeAnxietyCost * -0.5 +
  mnl_param$WalkingEgressCost * -0.086 +
  mnl_param$ParkingTicketCost * -0.5 +
  mnl_param$HomeActivityPrefersResidentialParking * 5.0

requestIds <- unique(mnl_param[grepl("Home", activityType)]$requestId)
chosen_home <- chosen[requestId %in% requestIds]
chosen_home_nonHome <- chosen_home[!startsWith(chargingPointType,"homelevel")]
requestIds_nonHome <- unique(chosen_home_nonHome$requestId)
home_nonHome_param <- mnl_param[requestId %in% requestIds_nonHome]
home_nonHome_search <- mnl_search[requestId %in% requestIds_nonHome]
home_nonHome_param[,.N,by=.(chargingPointType)]
home_nonHome_search[,.N,by=.(chargingPointType)]

requestIds <- unique(mnl_param[grepl("work",tolower(activityType))]$requestId)
chosen_work <- chosen[requestId %in% requestIds]
chosen_work_nonWork <- chosen_work[!startsWith(chargingPointType,"worklevel")]
requestIds_nonWork <- unique(chosen_work_nonWork$requestId)
home_nonWork_param <- mnl_param[requestId %in% requestIds_nonWork]
chosen_nonWork <- chosen[requestId %in% requestIds_nonWork]

chosen_nonWork[,.N,by=.(chargingPointType)]

test <- mnl_param[,.(n=.N),by=.(activityType,requestId)]

chosen_fc_ids <- unique(chosen[grepl("fc", chargingPointType)]$requestId)
param_fc <- mnl_param[requestId %in% chosen_fc_ids]
param_fc_home <- param_fc[activityType == "Home"]
chosen[requestId==112605]
chosen$idBis <- paste(chosen$requestId,chosen$parkingZoneId,sep="-")
mnl_param$idBis <- paste(mnl_param$requestId,mnl_param$parkingZoneId,sep="-")
res <- mnl_param[idBis %in% chosen$idBis]
mean(mnl_param[idBis %in% chosen$idBis]$result)
mean(mnl_param[,.SD[which.max(result)],by=.(requestId)]$result)

chosen[requestId == 93342]
mnl_param[requestId == 93342]
mnl_search[requestId == 93342]
sampled[requestId == 93342]
sampled[,.N,by=.(chargingPointType)]
mnl_param[,.N,by=.(chargingPointType)]


res <- home_nonHome_param[,.SD[which.max(result)],by=.(vehicleId)]
res[,.N,by=.(chargingPointType)]
res[chargingPointType=="publiclevel2(7.2|AC)"]

unique(stations[subSpace == 1186]$chrgType)
stationsAgg[taz == 1186]

test <- output1[,.(cost=mean(costInDollars)),by=.(mnlStatus,parkingZoneId,chargingPointType,parkingType)]
test2 <- test[cost > 0]

chosenSummary <- chosen[,.(n=.N,cost=mean(costInDollars)),b=.(chargingPointType)]
sampled[,.(n=.N,cost=mean(costInDollars)),b=.(chargingPointType)]


#res <- home_param[activityType=="Home",.SD[which.max(result)],by=.(vehicleId)]
res <- mnl_param[,.SD[which.max(result)],by=.(vehicleId)]
res[,.N,by=.(chargingPointType)]
sum(res[,.N,by=.(chargingPointType)]$N)


events <- readCsv(pp(workDir, "/2022Mars-Calibration/0.events.b1-1.csv.gz"))
events[type=="RefuelSessionEvent"][,.(.N,sumFuel=sum(fuel)),by=.(actType)]
events[type=="RefuelSessionEvent"][,.(.N,sumFuel=sum(fuel)),by=.(chargingPointType)]


mnl_param[,.(.N,mean(costInDollars)),by=.(chargingPointType)]


events7[type=="RefuelSessionEvent"][,.(sumFuel=sum(fuel)),by=.(actType)]


