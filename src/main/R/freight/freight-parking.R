setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
#install.packages("remotes")
#remotes::install_github("colinsheppard/colinmisc")
library('colinmisc')
library(dplyr)
library(ggplot2)
library(rapport)
library(sjmisc)
library(ggmap)
library(sf)
library(stringr)

workspaceDir <- normalizePath("~/Workspace/")
projectDir <- pp(workspaceDir, "/Data/FREIGHT/austin/")
existingParkingDir <- pp(projectDir, "beam/")
existingParkingFile <- pp(existingParkingDir, "blockgroup-centers.csv.gz")
parking <- readCsv(existingParkingFile)
parking$parkingType <- "Commercial" 
parking$chargingPointType <- "NoCharger"
parking$pricingModel <- "FlatFee"
parking$numStalls <- 2147483647
parking$feeInCents <- 0.0

parking2 <- parking[,.(
  pricingModel=first(pricingModel),
  numStalls=first(numStalls),
  feeInCents=first(feeInCents)),
  by=.(taz,parkingType,chargingPointType)]

#parking2[chargingPointType!="NoCharger"]$chargingPointType <- "UltraFast(250|DC)"
#parking2$pricingModel <- "FlatFee"
parkingTemplate <- parking2[,
                     .(numStalls=first(numStalls),feeInCents=first(feeInCents)),
                     by=.(taz,parkingType,chargingPointType,pricingModel)]
parkingTemplateBis <- parkingTemplate
parkingTemplateBis$parkingType <- "Depot"
parkingTemplate <- rbind(parkingTemplate, parkingTemplateBis)
#2147483647
write.csv(
  parkingTemplate,
  file = pp(existingParkingDir, "freight-parking-unlimited.csv"),
  row.names=F,
  quote=F)

parkingTemplate[chargingPointType=="NoCharger"]$numStalls <- 100
write.csv(
  parkingTemplate,
  file = pp(existingParkingDir, "freight-parking-high.csv"),
  row.names=F,
  quote=F)

#######################
## events
# /Users/haitamlaarabi/Workspace/Data/FREIGHT/austin/beam/runs/parking-sensitivity/2018_unlimited
outputFolder <- pp(projectDir, "beam/runs/parking-sensitivity/")
getEventFilePath <- function(runFolder, eventsName) {
  filePath <- pp(outputFolder, runFolder, "/", eventsName)
  if (file.exists(filePath)){
    return(filePath)
  } else {
    return(pp(outputFolder, runFolder, "/ITERS/it.0/0.events.csv.gz"))
  }
}

eventsUnlimited <- readCsv(getEventFilePath("2018_unlimited", "park2.0.events.csv.gz"))
eventsHigh <- readCsv(getEventFilePath("freight__2023-07-20_15-15-29_azh"))
eventsMedium <- readCsv(getEventFilePath("freight__2023-07-20_15-10-44_olo"))
eventsLow <- readCsv(getEventFilePath("freight__2023-07-20_14-19-18_kwr"))

getParkingSummary <- function(EVENTS_) {
  EVENTS_Freight <- EVENTS_[grepl("carrier", vehicle)]
  EVENTS_FreightPark <- EVENTS_Freight[
    type=="ParkingEvent", 
    c("time", "vehicle", "driver", "parkingZoneId", "parkingTaz", 
      "parkingType", "chargingPointType", "locationX", "locationY")
  ]
  return(EVENTS_FreightPark[
    chargingPointType=="None",
    .N,
    by=.(parkingZoneId,parkingTaz,parkingType)][
      order(parkingZoneId)])
}

eventsUnlimitedPark <- getParkingSummary(eventsUnlimited)
eventsHighPark <- getParkingSummary(eventsHigh)
eventsMediumPark <- getParkingSummary(eventsMedium)
eventsLowPark <- getParkingSummary(eventsLow)
eventsUnlimitedPark
eventsHighPark
eventsMediumPark
eventsLowPark

# eventsLow[grepl("carrier", vehicle)][
#   type=="ParkingEvent", 
#   c("time", "vehicle", "driver", "parkingZoneId", "parkingTaz", 
#     "parkingType", "chargingPointType", "locationX", "locationY")
# ]


getPTSummary <- function(EVENTS_) {
  EVENTS_FreightPT <- EVENTS_[grepl("carrier", vehicle)][
    type=="PathTraversal",
    c("time", "vehicle", "mode", "length", "departureTime", "arrivalTime",
      "primaryFuel", "secondaryFuel", "vehicleType")]
  return(EVENTS_FreightPT[,.(count=.N,VMT=sum(length)/1609.0,VHT=sum(arrivalTime-departureTime)/60.0),by=.(mode)])
}

eventsUnlimitedPT <- getPTSummary(eventsUnlimited)
eventsHighPT <- getPTSummary(eventsHigh)
eventsMediumPT <- getPTSummary(eventsMedium)
eventsLowPT <- getPTSummary(eventsLow)
eventsUnlimitedPT
eventsHighPT
eventsMediumPT
eventsLowPT



