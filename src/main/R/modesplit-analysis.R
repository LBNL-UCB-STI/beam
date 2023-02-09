setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("common/helpers.R")
source("common/theme.R")
library('colinmisc')

library(dplyr)
library(ggplot2)
library(ggrepel)
library(tidyr)

dataDir <- normalizePath("~/Workspace/Data")
workDir <- pp(dataDir,"/FREIGHT")
runDir <- pp(workDir,"/sfbay/beam/runs/7days/Dec06")

## Through RealizedMode file
# realizedMode <- readCsv(pp(runDir, "/e.6.realizedMode.csv"))
# summaryRealizedMode <- data.table(t(colSums(realizedMode)))[,-c("hours")]
# summaryRealizedMode2 <- data.table(gather(summaryRealizedMode, "mode", "count"))
# totCount <- sum(summaryRealizedMode2$count)
# summaryRealizedMode2$share <- summaryRealizedMode2$count/totCount

numActivitySimTrips <- 1479455
## freight payload
payload <- readCsv(pp(runDir, "/freight-merged-payload-plans.csv"))
tours <- readCsv(pp(runDir, "/freight-merged-tours.csv"))
numFRISMTrips <- nrow(payload) - nrow(tours)
shareFreightTrips <- numFRISMTrips / (numFRISMTrips + numActivitySimTrips)

## Through Events file
events <- readCsv(pp(runDir, "/ptmc.b.6.events.csv.gz"))
modeChoice <- events[type=="ModeChoice"]
modeChoice$mode2 <- modeChoice$mode
modeChoice[mode == "ride_hail"]$mode2 <- "Taxi/TNC"
modeChoice[mode == "ride_hail_pooled"]$mode2 <- "Taxi/TNC"
modeChoice[mode == "bike"]$mode2 <- "Bike"
modeChoice[mode == "car"]$mode2 <- "Car"
modeChoice[mode == "walk"]$mode2 <- "Walk"
modeChoice[mode == "walk_transit"]$mode2 <- "Transit"
modeChoice[mode == "bike_transit"]$mode2 <- "Transit"
modeChoice[mode == "drive_transit"]$mode2 <- "Transit"
modeChoice[mode == "ride_transit"]$mode2 <- "Transit"
modeChoice[mode == "car" & startsWith(person,"freightDriver-")]$mode2 <- "Freight"

modeSplit <- modeChoice[,.N,by=.(mode2)]
modeSplitNoFreight <- modeSplit[mode2 != "freight"]
modeSplitNoFreight$FCF005 <- modeSplitNoFreight$N/sum(modeSplitNoFreight$N)
modeSplitNoFreight <- modeSplitNoFreight[,-c("N")]

modeSplitWithFreight <- modeSplit
modeSplitWithFreight$FCF0035 <- modeSplitWithFreight$N/sum(modeSplitWithFreight$N)
modeSplitWithFreight <- modeSplitWithFreight[,-c("N")]

modeSplitData <- data.table::data.table(
  mode2 = c("Bike", "Car", "Taxi/TNC", "Walk", "Transit", "Freight"),
  ActivitySim = c(0.02, 0.74, 0.04, 0.12, 0.08, NA),
  NHTS = c(0.021, 0.716, 0.014, 0.176, 0.059, 0.014),
  BEAMValidated = c(0.02, 0.48+0.16+0.12, 0.04, 0.12, 0.08, NA)
)

modeSplitValidation <- data.table(gather(modeSplitData[modeSplitWithFreight, on="mode2"], source, value, -mode2))
ggplot(modeSplitValidation[mode2!="Freight"]) +
  geom_bar(aes(mode2, value, fill=source), stat = "identity", color = "white", position ="dodge") +
  theme_marain() +
  labs(x = "Mode", y = "Count", fill="")


## Path Traversals
pathTraversal <- events[type == "PathTraversal"]

## Link Stats
network <- readCsv(pp(runDir, "/network.csv.gz"))
linkTypes <- network[,c("linkId", "attributeOrigType")] 
linkStats <- readCsv(pp(runDir, "/b.6.linkstats.csv.gz"))
linkStatsPlus <- linkStats[linkTypes, on=c("link"="linkId")]

linkStatsSpeedAvg <- linkStatsPlus[,.(meanSpeed = mean((length/1609.0)/(traveltime/3600.0))),by=.(hour)]
ggplot(linkStatsSpeedAvg[hour <= 30]) +
  geom_line(aes(hour, meanSpeed)) +
  theme_marain() +
  labs(x = "Hour", y = "Speed (mph)")

linkStatsSpeedAvgByType <- linkStatsPlus[,.(meanSpeed = mean((length/1609.0)/(traveltime/3600.0))),by=.(hour, attributeOrigType)]
linkStatsSpeedAvgByTypeFiltered <- linkStatsSpeedAvgByType[!(attributeOrigType %in% c("busway", "null"))]
linkStatsSpeedAvgByTypeFiltered <- linkStatsSpeedAvgByTypeFiltered[!grepl("link", attributeOrigType)]
# linkStatsSpeedAvgByTypeFiltered$category <- linkStatsSpeedAvgByTypeFiltered$attributeOrigType
# linkStatsSpeedAvgByTypeFiltered[startsWith(attributeOrigType, "motorway")]$category <- "motorway/motorway_link"
# linkStatsSpeedAvgByTypeFiltered[startsWith(attributeOrigType, "primary")]$category <- "primary/primary_link"
# linkStatsSpeedAvgByTypeFiltered[startsWith(attributeOrigType, "secondary")]$category <- "secondary/secondary_link"
# linkStatsSpeedAvgByTypeFiltered[startsWith(attributeOrigType, "tertiary")]$category <- "tertiary/tertiary_link"
# linkStatsSpeedAvgByTypeFiltered[startsWith(attributeOrigType, "trunk")]$category <- "trunk/trunk_link"

ggplot(linkStatsSpeedAvgByTypeFiltered[hour <= 30]) +
  geom_line(aes(hour, meanSpeed, colour=attributeOrigType)) +
  theme_marain() +
  labs(x = "Hour", y = "Speed (mph)", colour = "Link Catergory")

### ActivitySim
# Bike: 2%
# Car: 74%
# Taxi/TNC: 4%
# Walk: 12%
# Walk to PT: 8% 
### NHTS
# Bike: 2%
# Car: 84%
# Taxi/TNC: 1%
# Walk: 12%
# Walk to PT: 1% 

### vehicleType

## mode

##

modesplit <- readCsv("beam-output-analysis/data/batch6_1.it15.realized_modesplit_trips.csv")
modesplit2 <- modesplit[V1 == 0][,-c("V1", "scenario", "technology", "cav")]

mapMode <- function(mode) {
  if(mode == "walk") {
    return("Walk")
  } else if (mode == "transit") {
    return("Transit")
  } else if (mode == "rhp") {
    return("Ridehail Pool")
  } else if (mode == "rh") {
    return("Ridehail")
  } else if (mode == "car") {
    return("Car")
  } else if (mode == "bike") {
    return("Bike")
  } else {
    return("None")
  }
}
colors <- c('blue'= '#377eb8', 'green'= '#227222', 'orange'= '#C66200', 'purple'= '#470467', 'red'= '#B30C0C',
  'yellow'= '#C6A600', 'light.green'= '#C0E0C0', 'magenta'= '#D0339D', 'dark.blue'= '#23128F',
  'brown'= '#542D06', 'grey'= '#8A8A8A', 'dark.grey'= '#2D2D2D', 'light.yellow'= '#FFE664',
  'light.purple'= '#9C50C0', 'light.orange'= '#FFB164', 'black'= '#000000')
cols <- c("Walk" = as.character(colors["green"]), 
          "Transit" = as.character(colors["blue"]), 
          "Ridehail Pool" = "mediumorchid", 
          "Ridehail" = as.character(colors["red"]),
          "Car" = as.character(colors["grey"]),
          "Bike" = as.character(colors["light.orange"]))
data <- data.table::as.data.table(data.frame(
  key=colnames(modesplit2),
  value=as.numeric(modesplit2[1])
) %>%
  rowwise() %>%
  mutate(mode = mapMode(key)) %>%
  arrange(mode))

labels <- c("Bike" = scales::percent(data[mode == "Bike"]$value),
            "Car" = scales::percent(data[mode == "Car"]$value),
            "Ridehail" = scales::percent(data[mode == "Ridehail"]$value),
            "Ridehail Pool" = scales::percent(data[mode == "Ridehail Pool"]$value), 
            "Transit" = scales::percent(data[mode == "Transit"]$value), 
            "Walk" = scales::percent(data[mode == "Walk"]$value)
            )

ggplot(data, aes(x = "", y = value, fill = mode, group=mode)) +
  geom_bar(stat = "identity", color = "white") +
  scale_color_manual(values = cols, aesthetics = c("colour", "fill")) +
  coord_polar("y", start = 0)+
  theme_void() +
  theme(legend.text=element_text(size=12)) +
  geom_label_repel(aes(x=1,y=cumsum(value)-value/5,label=scales::percent(value),fill=mode),size=5,show.legend=F, nudge_x = 1) +
  labs(fill="")

ggplot(data, aes(x = "", y = value, fill = mode, group=mode)) +
  geom_bar(stat = "identity", color = "white") +
  scale_color_manual(values = cols, aesthetics = c("colour", "fill")) +
  coord_polar("y", start = 0)+
  theme_void() +
  theme(legend.text=element_text(size=12)) +
  geom_label_repel(aes(x=1,y=1-(cumsum(value)-value/2),label=scales::percent(value)),size=5,show.legend=F, nudge_x = 1,fill="white") +
  labs(fill="")

