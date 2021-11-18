marain.blue <- '#3d79d9'
marain.orange <- '#f59805'
marain.red <- '#F54C36'
marain.yellow <- '#f5c136'
marain.light.grey <- '#d9d9d9'
marain.grey <- '#616161'
marain.dark.grey <- '#424242'

theme_marain <- function (base_size = 11, base_family = ""){
  half_line <- base_size/2
  theme(
    line = element_line(colour = "black", size = 0.5,
                        linetype = 1, lineend = "butt"),
    rect = element_rect(fill = "white", colour = "black",
                        size = 0.5, linetype = 1),
    text = element_text(family = base_family, face = "plain",
                        colour = "black", size = base_size,
                        lineheight = 0.9,  hjust = 0.5,
                        vjust = 0.5, angle = 0,
                        margin = margin(), debug = FALSE),

    axis.line = element_blank(),
    axis.text = element_text(size = rel(1.0), colour = "grey30"),
    axis.text.x = element_text(margin = margin(t = 0.8*half_line/2),
                               vjust = 1),
    axis.text.y = element_text(margin = margin(r = 0.8*half_line/2),
                               hjust = 1),
    axis.ticks = element_line(colour = "grey20"),
    axis.ticks.length = unit(half_line/2, "pt"),
    axis.title.x = element_text(margin = margin(t = 0.8 * half_line,
                                            b = 0.8 * half_line/2), size = rel(1.2)),
    axis.title.y = element_text(angle = 90,
                                margin = margin(r = 0.8 * half_line,
                                            l = 0.8 * half_line/2), size = rel(1.2)),

    legend.background = element_rect(colour = NA),
    legend.key = element_rect(fill = "white",colour = NA),
    legend.key.size = unit(1.2, "lines"),
    legend.key.height = NULL,
    legend.key.width = NULL,
    legend.text = element_text(size = rel(1.0)),
    legend.text.align = NULL,
    legend.title = element_text(hjust = 0, size = rel(1.2)),
    legend.title.align = NULL,
    legend.position = "right",
    legend.direction = NULL,
    legend.justification = "center",
    legend.box = NULL,

    panel.background = element_rect(fill = "white",colour = NA),
    panel.border = element_rect(fill=NA,colour='grey20'),
    panel.grid.major = element_line(),
    panel.grid.minor = element_line(size = 0.25),
    panel.margin.y = NULL, panel.ontop = FALSE,
    panel.grid = element_line(colour = "grey92"),
    strip.background = element_rect(fill = marain.dark.grey,colour = "black"),
    strip.text = element_text(colour = "white", face='bold', size = rel(1.5)),
    strip.text.x = element_text(margin = margin(t = half_line,
                                                b = half_line)),
    strip.text.y = element_text(angle = -90,
                                margin = margin(l = half_line,
                                                r = half_line)),
    strip.switch.pad.grid = unit(0.1, "cm"),
    strip.switch.pad.wrap = unit(0.1, "cm"),

    plot.background = element_rect(colour = "white"),
    plot.title = element_text(size = rel(1.5), face='bold', hjust = 0.0, margin = margin(b = half_line * 1.2)),
    plot.margin = margin(half_line, half_line, half_line, half_line),
  complete = TRUE)
}

