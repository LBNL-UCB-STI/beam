# Title     : TODO
# Objective : TODO
# Created by: kirill.mitin
# Created on: 2019-07-15
keep <- c("osmId","speedBeam","speedMedian","speedAvg")
speedData <- read.csv("speed/speed_data_compare_2.csv")
sdata <- speedData[complete.cases(speedData[keep]), ]
cd <- (sdata[4]- sdata[2])
cd1 <- cd[complete.cases(cd), ]

print(nrow(sdata))
print(length(cd1))
