

#d <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/test/input/beamville/lccm.csv'))
d <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/test/input/sf-light/lccm-update-vot.csv'))
dm <- melt(d,measure.vars=pp('class',1:6))
dm[,':='(latentClass=variable.1,variable.1=NULL)]
dm[alternative=='COLUMNS',':='(alternative=latentClass,latentClass='')]

# Explode references to ALL and COLUMNS to put into long
tour.types <- u(dm$tourType)[!u(dm$tourType)%in%c('ALL','COLUMNS')]
alts <- u(dm$alternative)[!u(dm$alternative)%in%c('ALL','COLUMNS',u(as.character(dm$latentClass)))]

tour.type.exp <- dm[tourType=='ALL',cbind(.SD,tourType.exploded=tour.types),by=c('model','variable','alternative','latentClass')]
tour.type.exp[,':='(tourType=tourType.exploded,tourType.exploded=NULL)]
dm <- rbindlist(list(dm[tourType!='ALL'],tour.type.exp),use.names=T)

alt.exp <- dm[alternative=='ALL',cbind(.SD,alternative.exploded=alts),by=c('model','tourType','variable','latentClass')]
alt.exp[,':='(alternative=alternative.exploded,alternative.exploded=NULL)]
dm <- rbindlist(list(dm[alternative!='ALL'],alt.exp),use.names=T)

setkey(dm,model,tourType,variable,alternative,latentClass)

#write.csv(dm[,.(model,tourType,variable,alternative,units,latentClass,value)],file='~/Dropbox/ucb/vto/beam-all/beam/test/input/beamville/lccm-long.csv',row.names=F,na="",quote=F)
write.csv(dm[,.(model,tourType,variable,alternative,units,latentClass,value)],file='~/Dropbox/ucb/vto/beam-all/beam/test/input/sf-light/lccm-long.csv',row.names=F,na="",quote=F)


