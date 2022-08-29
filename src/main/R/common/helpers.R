
dateTZ = "America/Los_Angeles"
datetimeref <- as.POSIXct('0001-01-01 00:00:00', tz = dateTZ)
datetimeref2019 <- as.POSIXct('2019-01-01 00:00:00', tz = dateTZ)

toDateTime <- function(secs, datedefault=datetimeref) {
  datedefault+secs
}

mergefiles <- function(directory){
  for (file in list.files(directory)){
    # if the merged dataset doesn't exist, create it
    filepath <- paste(directory, file, sep="/")
    
    if (!exists("dataset")){
      dataset <- readCsv(filepath)
    }
    
    # if the merged dataset does exist, append to it
    if (exists("dataset")){
      temp_dataset <-readCsv(filepath)
      dataset<-rbind(dataset, temp_dataset)
      rm(temp_dataset)
    }
    
  }
  return(dataset)
}

readCsv <- function(filepath) {
  return(data.table::fread(filepath, header=TRUE, sep=","))
}


sankeyDiagram <- function(source, target, value, title) {
  nodes=unique(as.character(c(source, target)))
  IDsource=match(source, nodes)-1
  IDtarget=match(target, nodes)-1
  library(plotly)
  p <- plot_ly(
    type = "sankey",
    orientation = "h",
    node = list(
      label = nodes,
      #color = c("blue", "blue", "blue", "blue", "blue", "blue"),
      pad = 15,
      thickness = 20,
      line = list(
        color = "black",
        width = 0.5
      )
    ),
    link = list(
      source = IDsource,
      target = IDtarget,
      value =  value
    )
  ) %>%
    layout(
      title = title,
      font = list(
        size = 16
      )
    )
  p
}


clusteringFreightBy <- function(data,cols,dataCbg,numClusters,labelData) {
  data[,hour:=as.integer(arrivalTime/3600)%%24]
  data_asSf <- st_as_sf(data,coords=cols,crs=4326,agr="constant")
  data_withCBG_asSf <- st_intersection(data_asSf,dataCbg)
  data_withCBG_asSf$X <- st_coordinates(data_withCBG_asSf$geometry)[,1]
  data_withCBG_asSf$Y <- st_coordinates(data_withCBG_asSf$geometry)[,2]
  data_withCBG <- data.table::as.data.table(data_withCBG_asSf)
  data_withCBG[,cluster:=kmeans(data_withCBG[,.(X,Y)],numClusters)$cluster]
  result <- data_withCBG[,.(count=.N,x2=mean(X),y2=mean(Y)),by=.(hour,cluster)]
  result$label <- labelData
  result
}

assignPostMilesGeometries <- function(TRUCK_DATA, POSTMILES_SHP) {
  getElementWithoutFlag <- function(list_elements, flag) {
    i <- 1
    while(list_elements[i] == '') {
      i <- i+1
    }
    return(list_elements[i])
  }
  TRUCK_DATA$X <- 0.0
  TRUCK_DATA$Y <- 0.0
  ### GEOCODING ###
  #TRUCK_DATA$lon <- 0.0
  #TRUCK_DATA$lat <- 0.0
  #TRUCK_DATA$geoAddress <- ""
  #nrow(TRUCK_DATA)
  postmiles <- st_read(POSTMILES_SHP)
  for(i in 1:nrow(TRUCK_DATA)) {
    county <- TRUCK_DATA$COUNTY[i]
    cnty <- TRUCK_DATA$CNTY[i]
    dist <- TRUCK_DATA$DIST[i]
    rte <- TRUCK_DATA$RTE[i]
    leg <- TRUCK_DATA$LEG[i]
    pm <- TRUCK_DATA$POST_MILE[i]
    filteredPM <- postmiles %>% filter(County == cnty, 
                                       District == dist, 
                                       Route == rte, 
                                       startsWith(as.character(PMc), as.character(pm)))
    if(nrow(filteredPM) == 0) {
      filteredPM <- postmiles %>% filter(County == cnty, District == dist, Route == rte)
    }
    if(nrow(filteredPM) > 1) {
      pm_numeric <- 0
      if(!is.na(as.numeric(pm))) {
        pm_numeric <- as.numeric(pm)
      } else {
        element <- getElementWithoutFlag(unlist(strsplit(pm, "R")), "R")
        element <- getElementWithoutFlag(unlist(strsplit(element, "L")), "L")
        element <- getElementWithoutFlag(unlist(strsplit(element, "T")), "T")
        element <- getElementWithoutFlag(unlist(strsplit(element, "M")), "M")
        pm_numeric <- as.numeric(element)
      }
      filteredPM <- filteredPM %>% rowwise() %>% mutate(diff = abs(as.numeric(PM) - pm_numeric))
      filteredPM <- filteredPM %>% filter(diff == min(filteredPM$diff))
    }
    if(nrow(filteredPM) > 0) {
      TRUCK_DATA$X[i] <- st_coordinates(filteredPM$geometry[1])[1]
      TRUCK_DATA$Y[i] <- st_coordinates(filteredPM$geometry[1])[2]
    }
    # address <- paste(county, " County, California, USA", sep="")
    # descriptions <- unlist(strsplit(TRUCK_DATA$DESCRIPTION[i],","))
    # if(length(descriptions) > 1) {
    #   address <- paste(str_trim(descriptions[2]), ", ", str_trim(descriptions[1]), ", ", address, sep="")
    # } else {
    #   address <- paste(str_trim(descriptions), ", ", address, sep="")
    # }
    # address <- ", CA"
    # address <- paste(TRUCK_DATA$DESCRIPTION[i], ", ", address, sep="")
    # address <- gsub("RTE.", "route", address)
    # address <- gsub("JCT.", "", address)
    # print(address)
    # result <- geocode(address, output = "latlona", source = "google")
    # TRUCK_DATA$lon[i] <- as.numeric(result[1])
    # TRUCK_DATA$lat[i] <- as.numeric(result[2])
    # TRUCK_DATA$geoAddress[i] <- as.character(result[3])
    # print(TRUCK_DATA[i])
  }
  data.table::fwrite(TRUCK_DATA, normalizePath(pp(freightDir,"/validation/2017_truck_aadtt_geocoded.csv")), quote=T)
  print("END OF assignPostMilesGeometries")
  return(TRUCK_DATA)
}

assignLinkIdToTruckAADTT <- function(NETWORK_CLEANED, NETWORK_CRS, TRUCK_AADTT, MAX_DISTANCE_IN_METER, EXPANSION_FACTOR) {
  network_sf <- st_transform(st_as_sf(
    NETWORK_CLEANED,
    coords = c("fromLocationX", "fromLocationY"),
    crs = NETWORK_CRS,
    agr = "constant"), 4326)
  
  truck_aadtt_sf <- st_as_sf(
    TRUCK_AADTT,
    coords = c("X", "Y"),
    crs = 4326,
    agr = "constant")
  
  network_sf$tempDistance <- NA
  truck_aadtt_sf$linkId <- NA
  expansionFactor <- EXPANSION_FACTOR
  maxDistanceToSearch <- MAX_DISTANCE_IN_METER
  counter1 <- 0
  counter2 <- 0
  for (row in 1:nrow(truck_aadtt_sf)) {
    if(row %% 100 == 0) {
      print(paste(row, " entries have been processed so far!"))
    }
    current_pm <- truck_aadtt_sf[row,]
    currentDist <- 20
    current_pm_links <- st_is_within_distance(current_pm, network_sf, dist = currentDist)
    list_of_links <- current_pm_links[1][[1]]
    while(length(list_of_links) < 1 & currentDist < maxDistanceToSearch) {
      currentDist <- currentDist * 2
      current_pm_links <- st_is_within_distance(current_pm, network_sf, dist = currentDist)
      list_of_links = current_pm_links[1][[1]]
    }
    selected_links <- network_sf[list_of_links,]
    if (length(list_of_links) == 0){
      counter1 <- counter1 + 1
      print("no links!!!")
    } else if (length(list_of_links) == 1){
      truck_aadtt_sf$linkId[row] <- network_sf[list_of_links,]$linkId
      network_sf <- network_sf[-list_of_links,]
      counter2 <- counter2 + 1
    } else {
      selected_pm <- truck_aadtt_sf[row,]
      selected_network <- network_sf[selected_links,]
      selected_network$tempDistance <- st_distance(selected_network, selected_pm)
      selected_network_filtered <- slice(selected_network, which.min(tempDistance))
      truck_aadtt_sf$linkId[row] <- selected_network_filtered$linkId
      network_sf <- network_sf %>% filter(linkId != selected_network_filtered$linkId)
      counter2 <- counter2 + 1
    }
  }
  print(paste("# unmatched links ", counter1))
  print(paste("# matched links ", counter2))
  return(truck_aadtt_sf)
}