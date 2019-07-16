# Title     : TODO
# Objective : TODO
# Created by: kirmit
# Created on: 2019-07-09
library(ggplot2)

keep <- c("osmId","speedBeam","speedMedian","speedAvg")
speedData <- read.csv("speed/speed_data_compare_2.csv")
sdata <- speedData[complete.cases(speedData[keep]), ]
cd <- (sdata[4]- sdata[2])
cd1 <- cd[complete.cases(cd), ]

print(subset(sdata, speedAvg < 3))
print(nrow(subset(sdata, speedAvg < 3)))

png(file = "scatterplot_diff_1.png", width = 3064, height = 2048, units = "px", pointsize = 48)

plot(x = 1:length(cd1), y = cd1, type = 'l')
# Save the file.
dev.off()

lines <- readLines(con <- file("speed/speed_data_part-59.csv", open = 'r'))

cat <- vector(mode = "numeric", length = length(lines))

for (i in 1:length(lines)) {
    cat[i] <- c(strsplit(lines[i], ","))
    png(file = paste0("59_speed_", i, ".png"),  width = 2048, height = 2048, units = "px", pointsize = 48)
    plot(x = 1:length(cat[[i]]), y = cat[[i]], type = 'l', xlab = "id", ylab = "mph")
    dev.off()
}

close(con)

df <- data.frame(speed=numeric(), idx=integer())
for (i in 1:length(cat)) {
    for (j in 1:length(cat[[i]])) {
        df[nrow(df) + 1,] = list(as.numeric(cat[[i]][j]), i)
    }
}

png(file = "59_speed.png", width = 3064, height = 2048, units = "px", pointsize = 48)
boxplot(df$speed ~ df$idx, data = df, xlab = "Segment", ylab = "Speed Mph", main = "Link 1")
dev.off()

linesMaxes <- readLines(ccc <- file("speed/speed_data_max.csv", open = 'r'))
maxes <- vector(mode = "integer", length = length(linesMaxes))

for (k in 1:length(linesMaxes)) {
    v <- unlist(strsplit(linesMaxes[k], ","))
    maxes[k] <- length(v)
}

close(ccc)

ggplot() + aes(maxes)+ geom_histogram(binwidth=1, colour="black", fill="white")
ggsave("max_speed_hist.png", width = 16, height = 9, dpi = 300)

hourSpeed <- read.csv("speed/speed_data_59.csv")
png(file = "59_speed_hour.png", width = 3064, height = 2048, units = "px", pointsize = 48)
boxplot(hourSpeed$speed ~ hourSpeed$hour, data = hourSpeed, xlab = "Hour", ylab = "Speed Mph", main = "Link 1")
dev.off()


# Give the chart file a name.
png(file = "scatterplot_1.png", width = 3064, height = 2048, units = "px", pointsize = 48)

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
