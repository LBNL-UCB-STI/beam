############################################
# Zooming 
############################################
zoom.to.scale <- function(level){
  approx(zoom.scales$level,zoom.scales$scale,level)$y
}

############################################
# High res queuing plots
############################################

stall.width <- 2.7 # standard 9' width in meters
stall.up <- data.frame(x0=c(1,1,0)*stall.width,y0=c(0,1,1)*stall.width*2,x1=c(1,0,0)*stall.width,y1=c(1,1,0)*stall.width*2)
stall.up <- data.frame(x0=c(0,0,-1)*stall.width,y0=c(-1,0,0)*stall.width*2,x1=c(0,-1,-1)*stall.width,y1=c(0,0,-1)*stall.width*2)
stall.down <- data.frame(x0=c(1,1,0)*stall.width,y0=c(1,0,0)*stall.width*2,x1=c(1,0,0)*stall.width,y1=c(0,0,1)*stall.width*2)
stall.down <- stall.up
stall.down$y0 <- stall.down$y0*-1
stall.down$y1 <- stall.down$y1*-1
move.stall <- function(stall,stall.width=2.7,right=0,up=0){
  stall$x0 <- stall$x0 + right*stall.width
  stall$x1 <- stall$x1 + right*stall.width
  stall$y0 <- stall$y0 + up*stall.width*2
  stall$y1 <- stall$y1 + up*stall.width*2
  stall
}
quad <- rbind(move.stall(stall.up,stall.width,0,0),move.stall(stall.up,stall.width,1,0),move.stall(stall.down,stall.width,1,0),move.stall(stall.down,stall.width,0,0))
quad.width <- stall.width*2
bank <- rbind(move.stall(quad,quad.width,0,0),move.stall(quad,quad.width,1,0),move.stall(quad,quad.width,-1,0))

#red <- readPNG("~/Dropbox/ucb/vto/slides/graphics/EV-red.png")
#green <- readPNG("~/Dropbox/ucb/vto/slides/graphics/EV-green.png")
#yellow <- readPNG("~/Dropbox/ucb/vto/slides/graphics/EV-yellow.png")

place.veh <- function(site,veh,stall.pos=c(-1,-1),quad.pos=c(0,0),updown='up'){
  if((stall.pos[2]>0 & updown=='up') | (stall.pos[2]<0 & updown=='down') ){
    #convert up to down or vice versa
    #veh <- apply(veh,3,function(x){ x[nrow(x):1,] })
    new.veh <- veh
    for(i in 1:4){
      new.veh[,,i] <- veh[dim(veh)[1]:1,,i]
    }
    veh <- new.veh
  }
  rasterImage(veh,site$x + quad.pos[1]*stall.width*2,
              site$y + quad.pos[2]*stall.width*2 + 0.15*stall.pos[2]*stall.width*2,
              site$x + quad.pos[1]*stall.width*2 + stall.pos[1]*stall.width,
              site$y + quad.pos[2]*stall.width*2 + 0.85*stall.pos[2]*stall.width*2)
}

############################################
# Charging
############################################
halos <- data.table(i=1:60,
                    outer.col=rgb(180/256,0,0,alpha=seq(0,1,length.out=60)),
                    middle.inner.col=rgb(180/256,0,0,alpha=rep(1,60)),
                    outer.scale=seq(1,2/3,length.out=60),
                    middle.scale=seq(2/3,1/3,length.out=60),
                    inner.scale=seq(1/3,0,length.out=60),key='i'
                   )

############################################
# Mobility
############################################
interp.loc.fast <- function(the.ts,start,end,route){
  the.links <- as.numeric(head(str_split(route," ")[[1]],-1))
  link.dat <- link.nodes[J(the.links),list(id,tt=length/freespeed,cum.tt=cumsum(length/freespeed),x0=from.x,y0=from.y,x1=to.x,y1=to.y)]
  dists.so.far <- (the.ts-start)/(end-start)*tail(link.dat$cum.tt,1)
  link.i <- sapply(dists.so.far,function(dist.so.far){ which(dist.so.far<=link.dat$cum.tt)[1] })
  frac.link <- (dists.so.far - c(0,link.dat$cum.tt)[link.i]) / link.dat$tt[link.i]
  res.x <- link.dat$x0[link.i] + (link.dat$x1[link.i] - link.dat$x0[link.i])*frac.link
  res.y <- link.dat$y0[link.i] + (link.dat$y1[link.i] - link.dat$y0[link.i])*frac.link
  return(list(res.x,res.y))
}
interp.loc <- function(t,start,end,route){
  setkey(link.nodes,id)
  ts <- dlply(data.frame(row=1:length(start),start,t,end),.(row),function(df){ c(df$start,df$t,df$end) })
  links <- lapply(str_split(route," "),function(ll){ as.numeric(head(ll,-1)) })
  results <- ldply(mapply(function(the.ts,the.links){ list(the.ts,the.links) },ts,links,SIMPLIFY=F),function(ll){ 
                    link.dat <- link.nodes[J(ll[[2]]),list(id,tt=length/freespeed,cum.tt=cumsum(length/freespeed),x0=from.x,y0=from.y,x1=to.x,y1=to.y)]
                    dist.so.far <- diff(ll[[1]][1:2])/(diff(ll[[1]][c(1,3)]))*tail(link.dat$cum.tt,1)
                    link.i <- which(dist.so.far<=link.dat$cum.tt)[1]
                    frac.link <- (dist.so.far - c(0,link.dat$cum.tt)[link.i]) / link.dat$tt[link.i]
                    res.x <- link.dat$x0[link.i] + diff(c(link.dat$x0[link.i],link.dat$x1[link.i]))*frac.link
                    res.y <- link.dat$y0[link.i] + diff(c(link.dat$y0[link.i],link.dat$y1[link.i]))*frac.link
                    data.frame(x=res.x,y=res.y)
              })
  return(list(results$x,results$y))
}
calc.direction <- function(x,y){
  if(length(x)==1){
    return(0)
  }else{
    the.dirs <- atan(diff(y)/diff(x)) + ifelse(diff(x)<0,pi,0)
    return(c(the.dirs[1],the.dirs))
  }
}

############################################
# Plotting
############################################
my.zoom <- function(cam,d.size){
  xlims <- cam$x+c(-0.5,0.5)*zoom.to.scale(cam$z.level) * d.size[1]
  ylims <- cam$y+c(-0.5,0.5)*zoom.to.scale(cam$z.level) * d.size[2]
  return(list(xlim=xlims,ylim=ylims))
}
bb.to.lims <- function(bb){
  list(xlim=bb[1,],ylim=bb[2,])
}
to.bb <- function(lims){
  bb <- as(extent(as.vector(t(makebbox(lims$ylim[2],lims$xlim[1],lims$ylim[1],lims$xlim[2])))), "SpatialPolygons")
  suppressWarnings(proj4string(bb) <- CRS("+init=epsg:3857"))
  bb
}
plot.map <- function(cam,lims){
  if(cam$z.level<16){
    if(sum(!is.na(map %over% to.bb(lims)))>0){
      plot(map.bb,col='#343332')
      plot(map[to.bb(lims),],col='#191a1a',border='#191a1a',add=T)
      return()
    }
  }
  plot(map.low,col='#343332',border='#343332',bg='#191a1a')
}
plot.segs <- function(cam,lims=NULL){
  if(is.null(lims))lims <- my.zoom(cam)
  if(cam$z.level<=11){
    types <- '3-arterial'
  }else if(cam$z.level<=14 & cam$z.level>11){
    types <- c('3-arterial','2-collector')
  }else{
    types <- c('3-arterial','2-collector','1-local')
  }
  inds <- with(link.nodes,type%in%types & (from.x>=lims$xlim[1] & from.x<=lims$xlim[2] & from.y>=lims$ylim[1] & from.y<=lims$ylim[2]) |
                  (to.x>=lims$xlim[1] & to.x<=lims$xlim[2] & to.y>=lims$ylim[1] & to.y<=lims$ylim[2]),list(from.x,from.y,to.x,to.y,col,lwd))
  the.lwds <- 1.5*(sapply(sapply(cam$z.level,min,18),max,14) - 14) + link.nodes$lwd[inds]
  segments(link.nodes$from.x[inds],link.nodes$from.y[inds],link.nodes$to.x[inds],link.nodes$to.y[inds],col=link.nodes$col[inds],lwd=the.lwds)
}
plot.buildings <- function(cam,lims=NULL){
  #if(cam$z.level>=16)plot(buildings[to.bb(lims),],col='#2C2C2B',border='#2C2C2B',add=T)
}
pinch <- function(x,scale=0.9){
  return(mean(x)+c(-scale,scale)*diff(x)/2)
}
plot.sites <- function(cam,lims,d.size){
  for(site.i in which(sites$x >= lims$xlim[1] & sites$x <= lims$xlim[2] & sites$y >= lims$ylim[1] & sites$y <= lims$ylim[2] )){
    if(cam$z.level>16){
      plot.quad(sites[site.i],col='white')
    }
    plot.charging.vehs(ch.events[J(cam$t,sites$id[site.i])],cam,d.size)
  }
}
plot.quad <- function(site,col='white',...){
  segments(site$x[1]+quad$x0,site$y[1]+quad$y0,site$x[1]+quad$x1,site$y[1]+quad$y1,col=col,...)
}
plot.charging.vehs <- function(vehs,cam,d.size){
  if(nrow(vehs)>0){
    if(!is.null(vehs[1]$plug.i) && !is.na(vehs[1]$plug.i)){
      for(veh.i in 1:nrow(vehs)){
        if(vehs[veh.i]$rank<=4){
          stall.pos <- list(c(-1,-1),c(1,-1),c(1,1),c(-1,1))[[vehs[veh.i]$rank]]
          quad.pos <- c(vehs[veh.i]$plug.i-1,0)
          vehs[veh.i,dir:=ifelse(rank<=2,pi/2,-pi/2)]
          vehs[veh.i,x:=site.x + quad.pos[1]*stall.width*2 + 0.5*stall.pos[1]*stall.width]
          vehs[veh.i,y:=site.y + quad.pos[2]*stall.width*2 + 0.6666*stall.pos[2]*stall.width*2]
          # plot halo
          if(vehs[veh.i]$is.charging & vehs[veh.i]$alpha==1){
            outer.cex <- max(cam$z.level - 10,8)
            the.lwd <- max((cam$z.level - 15)*4.5/9+1,1.5)
            halo.i <- floor(cam$t/dt)%%60+1
            points(rep(vehs[veh.i]$x,3),rep(vehs[veh.i]$y,3),cex=outer.cex*halos[J(halo.i),list(scale=c(outer.scale, middle.scale, inner.scale))]$scale,
                   col=halos[J(halo.i),list(col=c(outer.col, middle.inner.col, middle.inner.col))]$col,lwd=the.lwd)
          }
          if(cam$z.level>16)plot.veh(vehs[veh.i],cam,d.size,scale=NA)
        }
      }
    }
  }
}
plot.veh <- function(veh,cam,d.size,scale=1/20,arrow.ang=75*pi/180,...){
  the.col <- add.alpha(ifelse(is.na(veh$soc),'#777777',soc.colors[ceiling(veh$soc*100)]),min(veh$alpha,0.9999))
  the.lty <- ifelse(veh$alpha<1,0,1)
  veh.len <- ifelse(is.na(scale),4.5,zoom.to.scale(cam$z.level) * d.size[2] * scale)
  cord.len <- 1/3*veh.len*cos(arrow.ang/2)
  veh.pts <- data.frame(x0=c(2/3*veh.len*cos(veh$dir),cord.len*cos(pi+veh$dir-arrow.ang/2),cord.len*cos(pi+veh$dir+arrow.ang/2)),
                        y0=c(2/3*veh.len*sin(veh$dir),cord.len*sin(pi+veh$dir-arrow.ang/2),cord.len*sin(pi+veh$dir+arrow.ang/2)))
  polygon(veh$x+veh.pts$x0,veh$y+veh.pts$y0,col=the.col,lty=the.lty,...)
}
make.spline <- function(start,end,way.pts){
  predict(smooth.spline(seq(start,end,length.out=length(way.pts)),way.pts,all.knots=T,spar=0.3),seq(start,end))$y
}
make.knots <- function(beg,end,knot.form){
  return(beg + (end - beg)*(knot.form - head(knot.form,1))/(tail(knot.form,1)-head(knot.form,1)))
}
plot.clock <- function(t, d.size,the.col='white') {
  hour <- floor(t/3600)%%24 + (t/3600 - floor(t/3600))
  minute <- floor(t/60)%%60 + (t/60 - floor(t/60))
  x.to.y.ratio <- d.size[1]/d.size[2]
  t <- seq(0, 2*pi, length=13)[-13]
  tick.pos <- 0.056
  x <- cos(t)*tick.pos
  y <- sin(t)*tick.pos*x.to.y.ratio
  center.x <- 0.115
  center.y <- center.x*x.to.y.ratio

  # Circle with ticks
  grid.circle(x=center.x, y=center.y, r=unit(0.1, "npc"),gp=gpar(col=the.col,fill="#191a1a"))
  grid.segments(x+center.x, y + center.y, x*.9 +center.x, y*.9 +center.y, default="npc",gp=gpar(col=the.col))

  # Hour hand
  hourAngle <- pi/2 - (hour + minute/60)/12*2*pi
  hr.size <- .027
  grid.segments(center.x, center.y, center.x + hr.size*cos(hourAngle), center.y + x.to.y.ratio*hr.size*sin(hourAngle), default="npc", gp=gpar(lex=4,col=the.col))

  # Minute hand
  minuteAngle <- pi/2 - (minute)/60*2*pi
  grid.segments(center.x, center.y, center.x + hr.size*1.55*cos(minuteAngle), center.y + x.to.y.ratio*hr.size*1.55*sin(minuteAngle), default="npc", gp=gpar(lex=2,col=the.col))    
}
