setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
library('colinmisc')
library(dplyr)
library(ggplot2)
library(rapport)
library(sjmisc)
library(ggmap)
library(sf)
library(stringr)
library(testit)


activitySimDir <- normalizePath("~/Data/ACTIVITYSIM")

plans <- readCsv(pp(activitySimDir, "/2018/plans.csv.gz"))
#trips <- readCsv(pp(activitySimDir, "/2018/trips.csv.gz"))
persons <- readCsv(pp(activitySimDir, "/2018/persons.csv.gz"))
households <- readCsv(pp(activitySimDir, "/2018/households.csv.gz"))
blocks <- readCsv(pp(activitySimDir, "/2018/blocks.csv.gz"))


assert("NOT greated than population", length(unique(plans$person_id)) <= nrow(persons))

persons_summary <- persons[,c("person_id", "household_id")]
assert("NOT same number of persons", sum(households$hhsize) == nrow(persons_summary))

households_summary <- households[hh_cars %in% c("one", "two or more")][,c("household_id", "TAZ", "hhsize")]
persons_households <- merge(x=households_summary,y=persons_summary,by=c("household_id"),all=TRUE)[!is.na(TAZ)]

assert("NOT same number of households", length(unique(persons_households$household_id)) == nrow(households_summary))
assert("NOT same number of persons", nrow(persons_households) == sum(households_summary$hhsize))

traveling_persons_households <- persons_households[person_id %in% unique(plans$person_id)]

plans_summary <- plans[ActivityElement == "activity"][,c("person_id", "ActivityType", "x", "y")][person_id %in% traveling_persons_households$person_id]

assert("NOT same number of persons", nrow(traveling_persons_households) == length(unique(plans_summary$person_id)))

activities_location <- merge(x=plans_summary,y=traveling_persons_households,by=c("person_id"),all=TRUE)

write.csv(
  activities_location,
  file = pp(activitySimDir, "/2018/activities_location.csv"),
  row.names=FALSE,
  quote=FALSE)




#####

geminiDir <- normalizePath("~/Data/GEMINI")

#vehicles-8MaxEV-generated.csv

vehicles <- readCsv(pp(geminiDir, "/2022-07-05/_models/vehicles/", "vehicles-7Advanced-generated.csv"))
vehiclesBis <- readCsv(pp(geminiDir, "/2022-07-05/_models/vehicles/", "vehicles-7Advanced-generated-Bis.csv"))

vehicles$stateOfCharge <- as.character(vehicles$stateOfCharge)
vehicles$stateOfCharge <- ""
vehiclesBis$stateOfCharge <- as.character(vehiclesBis$stateOfCharge)
vehiclesBis$stateOfCharge <- ""
# result <- merge(x=vehicles,y=vehiclesBis,by=c("householdId","vehicleTypeId","vehicleId"),all.y=TRUE) 
result <- merge(x=vehicles,y=vehiclesBis,by=c("householdId","vehicleTypeId"),all.y=TRUE) 
result[is.na(stateOfCharge.x)]


vehicles.dist <- vehicles[,.(vehicles=.N),by=.(householdId)]
vehiclesBis.dist <- vehiclesBis[,.(vehiclesBis=.N),by=.(householdId)]
result <- merge(x=vehicles.dist,y=vehiclesBis.dist,by=c("householdId"),all.y=TRUE)
result$vehicleDiff <- result$vehiclesBis - result$vehicles


for (i in 1:length(vehiclesBis.dist)) {
  householdId <- vehiclesBis.dist[i]$householdId
  numVehicles <- vehiclesBis.dist[i]$vehiclesBis
  newNumVehicles <- vehicles.dist[householdId == householdId]$vehicles
  if(newNumVehicles < numVehicles) {
    
  }
}

tnc_single <- nrow(plans[trip_mode=="TNC_SINGLE"])
tnc_shared <- nrow(plans[trip_mode=="TNC_SHARED"])
tnc_single/(tnc_single+tnc_shared)
tnc_shared/(tnc_single+tnc_shared)



