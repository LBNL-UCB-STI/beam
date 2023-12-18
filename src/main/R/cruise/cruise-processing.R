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
library(hrbrthemes)
library(tidyr)

workDir <- normalizePath("~/Workspace/Data/Cruise")

## Testing road restriction
eventsNoRdRestriction <- readCsv(pp(workDir, "/no-road-restriction/0.events.csv.gz"))
eventsWithRdRestriction <- readCsv(pp(workDir, "/with-road-restriction/0.events.csv.gz"))
network <- readCsv(pp(workDir, "/network.csv.gz"))[,restricted:=linkFreeSpeed>12.0]
vehicleTypes <- c("BEV", "PHEV", "RH_Car", "RH_Car-wheelchair", "Car")
pt1 <- eventsNoRdRestriction[type=="PathTraversal"][vehicleType%in%vehicleTypes][,duration2:=arrivalTime-departureTime][,`:=`(IDX = 1:.N)]
pt2 <- eventsWithRdRestriction[type=="PathTraversal"][vehicleType%in%vehicleTypes][,duration2:=arrivalTime-departureTime][,`:=`(IDX = 1:.N)]


network_test <- network[,c("linkId", "linkFreeSpeed", "attributeOrigType", "restricted")]
network_test$linkId <- as.character(network_test$linkId)
pt1_test <- data.table::as.data.table(tidyr::separate_rows(pt1[,c("vehicle", "IDX", "links")], links, convert = TRUE))
pt1_test$links <- as.character(pt1_test$links)
pt2_test <- data.table::as.data.table(tidyr::separate_rows(pt2[,c("vehicle", "IDX", "links")], links, convert = TRUE))
pt2_test$links <- as.character(pt2_test$links)

pt1_network <- pt1_test[network_test, on=c("links"="linkId")]
pt1_network[!is.na(vehicle)][,.N,by=.(restricted)][order(restricted)]
pt2_network <- pt2_test[network_test, on=c("links"="linkId")]
pt2_network[!is.na(vehicle)][,.N,by=.(restricted)][order(restricted)]

mean(pt1$length)
mean(pt2$length)

mean(pt1$duration2, na.rm=T)
mean(pt2$duration2, na.rm=T)



network[,.N,by=.(attributeOrigType, restricted)][order(attributeOrigType)]

ggplot(network) + geom_histogram(aes(linkFreeSpeed))
ggplot(pt1) + geom_histogram(aes(length))
ggplot(pt2) + geom_histogram(aes(length))



median(pt1$length)

median(pt2$length)
count(network[linkFreeSpeed<=25])
count(network)
