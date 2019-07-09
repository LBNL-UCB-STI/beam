# Title     : TODO
# Objective : TODO
# Created by: kirmit
# Created on: 2019-07-09

speedData <- read.csv("compare_sf_1.csv")
sdata <- speedData[complete.cases(speedData), ]
sdata2 <- sdata[sdata[2] != 0.0, ]
cd <- (sdata2[4]- sdata2[2])

png(file = "scatterplot_diff.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(x = c(0:nrow(cd)), y = cd$speedAvg, type = 'l')
# Save the file.
dev.off()

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
