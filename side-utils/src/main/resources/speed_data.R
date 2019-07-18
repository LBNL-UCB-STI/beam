# Title     : TODO
# Objective : TODO
# Created by: kirill.mitin
# Created on: 2019-07-15
library(ggplot2)

keep <- c("osmId", "speedBeam", "speedMedian", "speedAvg")
speedData <- read.csv("speed/osm_way_segm.csv")
sdata <- speedData[complete.cases(speedData[keep]),]

cd <- transform(sdata, diffSpeed = speedBeam - speedAvg)

ggplot(cd, aes(x = diffSpeed, y = maxDev)) + geom_point() + geom_text(aes(label = speedAvg), check_overlap = TRUE) +
    labs(x = "Diff", y = "Observations") + scale_x_continuous(breaks = seq(-30, 30, 2))
ggsave("diff_plot.png", width = 32, height = 18, dpi = 300)

png(file = "diff_scatterplot.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(cd$maxDev ~ cd$diff,
data = sdata,
xlab = "Diff",
axes = FALSE,
ylab = "Observations",
main = "Beam vs Uber"
)
axis(side = 1, at = seq(-40, 40, by = 2))
axis(side = 2, at = seq(-50, 1000, by = 20))

# Save the file.
dev.off()
