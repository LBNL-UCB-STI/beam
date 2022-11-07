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
