# Title     : TODO
# Objective : TODO
# Created by: kirill.mitin
# Created on: 2019-07-15
library(ggplot2)

keep <- c("osmId", "speedBeam", "speedMedian", "speedAvg", "cat")
speedData <- read.csv("speed/compare_no_locals.csv")
sdata <- speedData[complete.cases(speedData[keep]), ]

cd <- transform(sdata, diffSpeed = speedBeam - speedAvg)

ggplot(cd, aes(x = diffSpeed, y = maxDev, color = cat)) +
    geom_point() +
    geom_text(aes(label = osmId), check_overlap = TRUE) +
    labs(x = "Diff", y = "Observations") +
    scale_x_continuous(breaks = seq(- 30, 30, 2)) +
    scale_color_manual(
    breaks = c("motorway", "motorway_link", "primary", "primary_link", "road", "secondary", "secondary_link", "tertiary", "tertiary_link", "trunk", "trunk_link","unclassified"),
    values = c("#66FF00",  "#33FF00",       "#FF3300", "#FF0000",      "#330033", "#FFFF00", "#FFFF33",       "#CC0099", "#FF00CC",        "#3300FF", "#3300CC", "#33CCFF"))
ggsave("diff_plot_no_locals_id.png", width = 32, height = 18, dpi = 300)

png(file = "diff_scatterplot_no_locals.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(cd$maxDev ~ cd$diff,
data = cd,
xlab = "Diff",
axes = FALSE,
ylab = "Observations",
main = "Beam vs Uber"
)
axis(side = 1, at = seq(- 40, 40, by = 2))
axis(side = 2, at = seq(- 50, 1000, by = 20))

# Save the file.
dev.off()

png(file = "scatterplot_diff_no_locals.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(x = 1:nrow(cd), y = cd$diff, type = 'l')
# Save the file.
dev.off()

