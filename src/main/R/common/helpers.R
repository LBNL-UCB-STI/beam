
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


