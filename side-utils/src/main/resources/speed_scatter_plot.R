# Title     : TODO
# Objective : TODO
# Created by: kirmit
# Created on: 2019-07-09

speedData <- read.csv("compare_sf-light_simple_2.csv")

# Give the chart file a name.
png(file = "scatterplot.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(speedData$speedMedian ~ speedData$speedBeam,
    data = speedData,
    xlab = "Matsim Speed",
    axes = FALSE,
    ylab = "Uber Avg Speed",
    main = "Beam vs Uber"
)
axis(side = 1, at = seq(0, 50, by = 2))
axis(side = 2, at = seq(0, 80, by = 5))
abline(lm(speedData$speedBeam ~ speedData$speedMedian), col = "red")

# Save the file.
dev.off()
