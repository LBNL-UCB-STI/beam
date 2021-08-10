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

workDir <- "/Users/haitamlaarabi/Data/GEMINI/"

source("/Users/haitamlaarabi/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oaklandMap <- ggmap::get_googlemap("oakland california", zoom = 13, maptype = "roadmap")
shpFile <- pp(workDir, "shapefile/Oakland+Alameda+TAZ/Transportation_Analysis_Zones.shp")
oaklandCbg <- st_read(shpFile)

charging_sessions <- readCsv("/Users/haitamlaarabi/Data/GEMINI/2021Jul30-Oakland/BASE0/2021-08-01.refuelSession.csv")
chargingEventsSf <- st_as_sf(charging_sessions, coords = c("locationX", "locationY"), crs = 4326, agr = "constant")

ggmap(oaklandMap) +
  theme_marain() +
  geom_sf(data = chargingEventsSf, aes(color = as.character(parkingZoneId)), inherit.aes = FALSE) +
  labs(color = "TAZs")

outChargingSession <- st_intersection(chargingEventsSf, oaklandCbg)

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

source("/Users/haitamlaarabi/Documents/Workspace/scripts/common/keys.R")
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
activitySimDir <- "/Users/haitamlaarabi/Data/ACTIVITYSIM/activitysim-plans-base-2010-cut-718k-by-shapefile"
#activitySimDir <- "/Users/haitamlaarabi/Data/ACTIVITYSIM/plans-base-2010"
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
