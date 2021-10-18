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
events <- readCsv(pp(workDir, "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC0.csv.gz"))


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
rse100_SC3 <- readCsv(pp(workDir, events100_SC3))[type=='RefuelSessionEvent']

events100_SC2 <- "/2021Aug22-Oakland/BATCH3/events/filtered.0.events.SC2.csv.gz"
rse100_SC2 <- readCsv(pp(workDir, events100_SC2))[type=='RefuelSessionEvent']

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


c <- a[b, on="chargingPointType"][,fuelShare:=fuel3/fuel2]


rse100_3[startsWith(parkingZoneId, "AO")&actType=="Home"]
rse100[startsWith(parkingZoneId, "AO")&actType=="Home"]

##

# mnl <- readCsv(pp(workDir, "/beamLog.csv"))
# mnl$chargingType <- "NoCharger"
# mnl[grepl("publicfc",parkingZoneId)]$chargingType <- "publicfc"
# mnl[grepl("publiclevel2",parkingZoneId)]$chargingType <- "publiclevel2"
# mnl[grepl("homelevel1",parkingZoneId)]$chargingType <- "homelevel1"
# mnl[grepl("homelevel2",parkingZoneId)]$chargingType <- "homelevel2"
# mnl[grepl("worklevel2",parkingZoneId)]$chargingType <- "worklevel2"
# mnl[grepl("publicxfc",parkingZoneId)]$chargingType <- "publicxfc"
# 
# parkingFunctions <- mnl[label=="ParkingFunctions"]
# chargingFunctions <- mnl[label=="ChargingFunctions"]
# parkingZoneSearchResult <- mnl[label=="ParkingZoneSearchResult"]
# parkingZonesSampled <- mnl[label=="ParkingZonesSampled"]

write.csv(parkingFunctions,file = pp(workDir, "/parkingFunctions.csv.gz"),row.names=FALSE,quote=FALSE,na="")
write.csv(chargingFunctions,file = pp(workDir, "/chargingFunctions.csv.gz"),row.names=FALSE,quote=FALSE,na="")
write.csv(parkingZoneSearchResult,file = pp(workDir, "/parkingZoneSearchResult.csv.gz"),row.names=FALSE,quote=FALSE,na="")
write.csv(parkingZonesSampled,file = pp(workDir, "/parkingZonesSampled.csv.gz"),row.names=FALSE,quote=FALSE,na="")

parkingFunctions <- readCsv(pp(workDir, "/parkingFunctions.csv.gz"))
chargingFunctions <- readCsv(pp(workDir, "/chargingFunctions.csv.gz"))
parkingZoneSearchResult <- readCsv(pp(workDir, "/parkingZoneSearchResult.csv.gz"))
parkingZonesSampled <- readCsv(pp(workDir, "/parkingZonesSampled.csv.gz"))

chargingFunctions[,.N,by=.(chargingType)]


chargingFunctions[grepl("homelevel1(1.8|AC)",parkingZoneId)]