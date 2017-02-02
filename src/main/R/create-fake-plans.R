
tab <- expand.grid(list(o=1:9,d=1:9))
the.map <- data.frame(x=rep(0:2,3),y=rep(0:2,each=3))

t <- 0
for(i in 1:nrow(tab)){
  the.min <- t%%60
  the.hr <- floor(t/60)+8
  the.str <- pp('      <act end_time="',the.hr,':',formatC(the.min,width = 2, format = "d", flag = "0"),':00" type="Home" x="',the.map$x[tab$o[i]],'" y="',the.map$y[tab$o[i]],'"/>
      <leg mode="car"/>')
  my.cat(the.str)
  t <- t+1
  the.min <- t%%60
  the.hr <- floor(t/60)+8
  the.str <- pp('      <act end_time="',the.hr,':',formatC(the.min,width = 2, format = "d", flag = "0"),':00" type="Home" x="',the.map$x[tab$d[i]],'" y="',the.map$y[tab$d[i]],'"/>
      <leg mode="car"/>')
  my.cat(the.str)
  t <- t+1
}




