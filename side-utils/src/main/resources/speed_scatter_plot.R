# Title     : TODO
# Objective : TODO
# Created by: kirmit
# Created on: 2019-07-09

speedData <- read.csv("compare_sf.csv")
sdata <- speedData[complete.cases(speedData), ]
# Give the chart file a name.
png(file = "scatterplot.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(sdata$speedMedian ~ sdata$speedBeam,
    data = sdata,
    xlab = "Matsim Speed",
    axes = FALSE,
    ylab = "Uber Avg Speed",
    main = "Beam vs Uber"
)
axis(side = 1, at = seq(0, 50, by = 2))
axis(side = 2, at = seq(0, 80, by = 5))
abline(lm(sdata$speedBeam ~ sdata$speedMedian), col = "red")

# Save the file.
dev.off()
