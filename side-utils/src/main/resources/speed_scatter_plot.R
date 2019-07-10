# Title     : TODO
# Objective : TODO
# Created by: kirmit
# Created on: 2019-07-09
library(ggplot2)

speedData <- read.csv("compare_sf.csv")
sdata <- speedData[complete.cases(speedData), ]
sdata2 <- sdata[sdata[2] != 0.0, ]
cd <- (sdata2[4]- sdata2[2])
cd1 <- cd[complete.cases(cd), ]

png(file = "scatterplot_diff.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(x = 1:length(cd1), y = cd1, type = 'l')
# Save the file.
dev.off()

lines <- readLines(file("speed/speed_data-1.csv", open = 'r'))

cat <- vector(mode = "numeric", length = length(lines))

for (i in 1:length(lines)) {
    cat[i] <- c(strsplit(lines[i], ","))
    png(file = paste0("1_speed_", i, ".png"),  width = 2048, height = 2048, units = "px", pointsize = 48)
    plot(x = 1:length(cat[[i]]), y = cat[[i]], type = 'l', xlab = "id", ylab = "mph")
    dev.off()
}

#png(file = "1_speed.png", width = 3064, height = 2048, units = "px", pointsize = 48)
#plot(x = cat, type = 'l')
#dev.off()

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
