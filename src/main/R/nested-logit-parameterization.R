# system.time(source('/Users/critter/Dropbox/ucb/ucb_smartcities_all/sandbox/colinsheppard/src/main/R/nested-logit-parameterization.R'))

load.libraries(c('XML','doMC','flexclust'))
registerDoMC(4)

out.dir <- 'sf-bay_2016-09-20_09-32-54'
out.dir <- 'sf-bay_2016-09-20_11-54-57'
out.dir <- 'sf-bay_2016-09-20_13-58-50'
out.dir <- 'sf-bay_2016-09-20_16-32-58'
out.dir <- 'sf-bay_2016-09-20_18-02-51'
out.dir <- 'sf-bay_2016-09-20_19-45-23'
out.dir <- '0-nested-logit-sf-bay_2016-09-15_14-17-03'

matsim.shared <- '/Users/critter/Dropbox/ucb/vto/beam-colin/'

nl.xml <- xmlParse(pp(matsim.shared,"parameterization/nested-logit.xml"))

extract.nl.model <- function(node.set){
  the.data <- list(elasticity=as.numeric(xmlValue(node.set[['elasticity']])))
  sub.set <- getNodeSet(node.set,'./nestedLogit')
  if(length(sub.set)==0){
    # leaf node
    the.data$utility=parse.params(node.set[['utility']])
  }else{
    for(sub.i in 1:length(sub.set)){
      #the.name <- xmlGetAttr(sub.set[[sub.i]][['nestedLogit']],'name')
      the.name <- xmlGetAttr(sub.set[[sub.i]],'name')
      the.data[[the.name]] <- extract.nl.model(sub.set[[sub.i]])
    }
  }
  return(the.data)
}

parse.params <- function(param.set){
  data.table(name=xpathSApply(param.set,'./param', xmlGetAttr,'name'),
              type=xpathSApply(param.set,'./param', xmlGetAttr,'type'),
              value=as.numeric(xpathSApply(param.set,'./param',xmlValue)))
}

# for debugging
#node.set <- getNodeSet(nl.xml,'/models/nestedLogit')[[model.i]]
#sub.set <- getNodeSet(node.set,'./nestedLogit')
#node.set <- sub.set[[1]]
#node.set <- sub.set[[2]]

model.names <- xpathSApply(nl.xml,'/models/nestedLogit', xmlGetAttr,'name')
model <- list()
for(model.i in 1:length(model.names)){
  # recurse the models and store the nodal data in nested lists
  model[[model.names[model.i]]] <- extract.nl.model(getNodeSet(nl.xml,'/models/nestedLogit')[[model.i]])
}

# Create data to apply
#  Note that units for distance are in miles and time in hours
att.values.orig <- data.table(read.csv(pp(matsim.shared,"parameterization/param-value-bounds.csv"),stringsAsFactors=F))
att.values.expanded <- list()
expansion.num <- 3
for(i in 1:nrow(att.values.orig)){
  if(is.na(att.values.orig[i]$min.value)){
    att.values.expanded[[att.values.orig[i]$attribute]] <- as.numeric(str_split(att.values.orig[i]$discrete.values,";")[[1]])
  }else{
    att.values.expanded[[att.values.orig[i]$attribute]] <- seq(att.values.orig[i]$min.value,att.values.orig[i]$max.value,length.out=expansion.num)
  }
}
att.value.combs <- data.table(expand.grid(att.values.expanded))
att.value.combs <- att.value.combs[remainingTravelDistanceInDay >= nextTripTravelDistance]

# Load data from a BEAM run to apply
do.or.load(pp('/Users/critter/Documents/matsim/pev/',out.dir,'/ITERS/it.0/run0.0.nested.logit.choices.Rdata'),args.to.save='nl',function(){
  my.cat('Parsing events data...')
  load(pp('/Users/critter/Documents/matsim/pev/',out.dir,'/ITERS/it.0/run0.0.events.Rdata'),verb=T)
  ev <- df[type=='NestedLogitDecisionEvent']
  rm('df')
  ev[,chargerAttributes:=as.character(chargerAttributes)]
  ev[,row:=1:nrow(ev)]
  part <- lapply(str_split(ev$chargerAttributes,"Alt"),function(ss){
        lapply(str_split(ss,'='), function(sss){ 
            if(length(sss)>1){ 
              unlist(sapply(head(str_split(sss[2],';')[[1]],-1),function(ssss){ lapply(str_split(ssss,":"),function(sssss){ streval(pp('c(',sssss[1],'=', sssss[2],')')) }) }, USE.NAMES=F))
            }else{
              NA
            } 
        }) 
  })
  whole <- ldply(setNames(part,as.character(1:length(part))),function(ll){ 
        ldply(ll,function(ll2){ 
              if(is.na(ll2[1])){ data.frame() }else{data.frame(t(ll2))}}) 
        },.id='row',.parallel=T)
  ch.att <- data.table(whole)
  ev[,chargerAttributes:=NULL]
  nl <- join.on(ch.att,ev,'row','row',c('isHomeActivity','remainingRange','type','remainingTravelDistanceInDay','decisionEventId','plannedDwellTime','nextTripTravelDistance','isBEV','time','searchRadius'))
  list(nl=nl)
  #save(nl,file=pp('/Users/critter/Documents/matsim/pev/',out.dir,'/ITERS/it.0/run0.0.nested.logit.choices.Rdata'))
})

param.coeffs <- data.table(read.csv(pp(matsim.shared,"parameterization/param-coeff-bounds.csv"),stringsAsFactors=F))
param.coeffs.default <- copy(param.coeffs)
param.coeffs.default[,':='(value=default,default=NULL,comments=NULL)]
param.coeffs.expanded <- list()
expansion.num <- 3
for(i in 1:nrow(param.coeffs)){
  if(is.null(param.coeffs.expanded[[param.coeffs[i]$utility.function]])){
    param.coeffs.expanded[[param.coeffs[i]$utility.function]] <- list()
  }
  if(is.na(param.coeffs[i]$min.coeff)){
    param.coeffs.expanded[[param.coeffs[i]$utility.function]][[param.coeffs[i]$attribute]] <- as.numeric(str_split(param.coeffs[i]$discrete.coeffs,";")[[1]])
  }else{
    if(expansion.num==3){
      param.coeffs.expanded[[param.coeffs[i]$utility.function]][[param.coeffs[i]$attribute]] <- c(param.coeffs[i]$min.coeff,param.coeffs[i]$default,param.coeffs[i]$max.coeff)
    }else{
      param.coeffs.expanded[[param.coeffs[i]$utility.function]][[param.coeffs[i]$attribute]] <- seq(param.coeffs[i]$min.coeff,param.coeffs[i]$max.coeff,length.out=expansion.num)
    }
  }
}
param.coeffs <- data.table(melt(param.coeffs.expanded))
param.coeffs[,':='(utility.function=L1,attribute=L2,L1=NULL,L2=NULL)]
coeffs <- param.coeffs.default

get.sub.names <- function(nest){
  return(names(nest)[!names(nest)%in%c("elasticity","utility")])
}

att.values <- melt(att.value.combs[1])
nest <- model[[1]]$yesCharge$genericSitePlugTypeAlternative
nest.name <- 'genericSitePlugTypeAlternative'
nest <- model[[1]]$yesCharge
nest.name <- 'yesCharge'
nest <- model[[1]]$noCharge
nest.name <- 'noCharge'
nest <- model[[1]]$noCharge$abort
nest.name <- 'top'
nest <- model[[1]]
nest.name <- 'abort'
eval.site.alt <- F
att.values[variable=='numChargers',value:=5]

calc.nl.probs <- function(nest,nest.name='top',coeffs,att.values,eval.site.alt=F,alts.from.beam=F){
  elast <- coeffs[attribute=="elasticity" & utility.function==nest.name]$value
  if(length(get.sub.names(nest))==0 & (nest.name!='genericSitePlugTypeAlternative' | eval.site.alt)){
    if(alts.from.beam){
      setkey(att.values,variable)
      util.tab <- join.on(join.on(coeffs[attribute!="elasticity" & utility.function==nest.name],u(att.values),'attribute','variable',NULL,'att.'),nest$utility,'attribute','name','type')
    }else{
      util.tab <- join.on(join.on(coeffs[attribute!="elasticity" & utility.function==nest.name],att.values,'attribute','variable',NULL,'att.'),nest$utility,'attribute','name','type')
    }
    util.tab[type=="MULTIPLIER",util:=att.value*value]
    util.tab[type=="INTERCEPT",util:=value]
    #my.cat(pp('util for ',nest.name,' = ',exp(sum(util.tab$util)/elast)))
    return(list(exp.max.util=exp(sum(util.tab$util)/elast)))
  }else{
    sum.exp.of.exp.max.util <- 0
    cond.probs <- list()
    if(nest.name=='genericSitePlugTypeAlternative'){
      n.chargers <- att.values[variable=='numChargers']$value[1]
      if(n.chargers>0){
        for(i in 1:n.chargers){
          if(alts.from.beam){
            result <- calc.nl.probs(nest,nest.name,coeffs,att.values[alt==i,],eval.site.alt=T,alts.from.beam=alts.from.beam)
          }else{
            result <- calc.nl.probs(nest,nest.name,coeffs,att.values,eval.site.alt=T,alts.from.beam=alts.from.beam)
          }
          exp.of.exp.max.util <- result$exp.max.util
          if("cond.probs" %in% names(result))cond.probs <- c(cond.probs,result$cond.probs) 
          cond.probs[[pp('genericSitePlugTypeAlternative',i)]] <- exp.of.exp.max.util
          sum.exp.of.exp.max.util <- sum.exp.of.exp.max.util + exp.of.exp.max.util
        }
        for(i in 1:n.chargers){
          cond.probs[[pp('genericSitePlugTypeAlternative',i)]] <- cond.probs[[pp('genericSitePlugTypeAlternative',i)]] / sum.exp.of.exp.max.util
        }
      }else{
        cond.probs[['genericSitePlugTypeAlternative']] <- 0
      }
    }else{
      for(sub.name in get.sub.names(nest)){
        result <- calc.nl.probs(nest[[sub.name]],sub.name,coeffs,att.values,eval.site.alt=F,alts.from.beam=alts.from.beam)
        exp.of.exp.max.util <- result$exp.max.util
        if("cond.probs" %in% names(result))cond.probs <- c(cond.probs,result$cond.probs)
        cond.probs[[sub.name]] <- exp.of.exp.max.util
        sum.exp.of.exp.max.util <- sum.exp.of.exp.max.util + exp.of.exp.max.util
      }
      for(sub.name in get.sub.names(nest)){
        if(sum.exp.of.exp.max.util>0){
          cond.probs[[sub.name]] <- cond.probs[[sub.name]] / sum.exp.of.exp.max.util
        }else{
          cond.probs[[sub.name]] <- 0
        }
      }
    }
    if(nest.name=='top'){
      cond.probs[['top']] <- 1
      return(marginalize.probs(nest,'top',cond.probs,att.values))
    }else{
      return(list(exp.max.util=sum.exp.of.exp.max.util^elast,cond.probs=cond.probs))
    }
  }
}

marginalize.probs <- function(nest,nest.name,cond.probs,att.values){
  if(length(get.sub.names(nest))==0 & (nest.name!='genericSitePlugTypeAlternative')){
    return(streval(pp("c(",nest.name,"=",cond.probs[[nest.name]],")")))
  }else{
    result <- c()
    if(nest.name=='genericSitePlugTypeAlternative'){
      n.chargers <- att.values[variable=='numChargers']$value
      if(n.chargers>0){
        for(i in 1:n.chargers){
          streval(pp("result['genericSitePlugTypeAlternative",i,"'] = cond.probs[['genericSitePlugTypeAlternative",i,"']]"))
        }
      }
    }else{
      for(sub.name in get.sub.names(nest)){
        result <- c(result,cond.probs[[nest.name]] * marginalize.probs(nest[[sub.name]],sub.name,cond.probs,att.values))
      }
    }
    return(result)
  }
}

#probs <- calc.nl.probs(model[[1]],'top',coeffs,att.values,alts.from.beam=T)
#probs <- calc.nl.probs(model[[1]],'top',coeffs,att.values)

evaluate.att <- function(att.i,alts.from.beam=F,param.coeffs.to.use=param.coeffs.default){
  if(alts.from.beam){
    suppressWarnings(att.values <- melt(nl[decisionEventId==att.i,list(remainingRange,remainingTravelDistanceInDay,nextTripTravelDistance,plannedDwellTime,isHomeActivity,isHomeActivityAndHomeCharger,isBEV,isAvailable,cost,chargerCapacity,distanceToActivity,searchRadius,alt=1:length(row),numChargers=length(row))],id.vars='alt'))
  }else{
    suppressWarnings(att.values <- melt(att.value.combs[att.i]))
  }
  return(calc.nl.probs(model[[1]],'top',param.coeffs.to.use,att.values,alts.from.beam=alts.from.beam))
}

if(F){
  system.time(results <- rbindlist(alply(sample(1:nrow(att.value.combs),10000),1,function(i){ data.table(row=i,t(evaluate.att(i))) },.parallel=T),fill=T))
  system.time(results <- rbindlist(alply(1:nrow(att.value.combs),1,function(i){ data.table(t(evaluate.att(i))) },.parallel=T),fill=T))

  save(results,file=pp(matsim.shared,"parameterization/sensitivity.Rdata"))
}

#if(F){
do.or.load(pp('/Users/critter/Documents/matsim/pev/',out.dir,'/ITERS/it.0/clustered-attribute-values.Rdata'),function(){
  my.cat('Clustering logit decision events...')
  # Look at clustering the alternative inputs from BEAM
  nl.agg <- nl[isHomeActivity==0,list(time=time[1],remainingRange=remainingRange[1],remainingTravelDistanceInDay=remainingTravelDistanceInDay[1],nextTripTravelDistance=nextTripTravelDistance[1],plannedDwellTime=plannedDwellTime[1],isHomeActivity=isHomeActivity[1],isHomeActivityAndHomeCharger=any(isHomeActivityAndHomeCharger==1),isBEV=isBEV[1],numAvailable=sum(isAvailable),cost=mean(cost),distanceToActivity=median(distanceToActivity),searchRadius=searchRadius[1],numChargers=length(row),fracFast=sum(chargerCapacity>20)/length(row)),by='decisionEventId']

  dat <- as.matrix(nl.agg[,list(time=time/max(time),remainingRange=remainingRange/max(remainingRange),remainingTravelDistanceInDay=remainingTravelDistanceInDay/max(remainingTravelDistanceInDay),nextTripTravelDistance=nextTripTravelDistance/max(nextTripTravelDistance),plannedDwellTime=plannedDwellTime/max(plannedDwellTime),isHomeActivity=isHomeActivity,isHomeActivityAndHomeCharger=as.numeric(isHomeActivityAndHomeCharger),isBEV=isBEV,numAvailable=numAvailable,cost=cost/max(cost),distanceToActivity=distanceToActivity/max(distanceToActivity),searchRadius=searchRadius/max(searchRadius),numChargers=numChargers/max(numChargers),fracFast=fracFast)])

  clust <- kmeans(dat,50,iter.max = 30)
  # Variation explained by clusters:
  (clust$totss - clust$tot.withinss)/clust$totss
  # Find decison IDs closest to center of each cluster
  dists <- dist2(dat,clust$centers)
  representative.members <- apply(dists,2,which.min)
  nl.rep <- nl.agg[representative.members]
  nl.rep[,weight:=clust$size]

  # Analyze the clusters over time
  #ggplot(melt(nl.rep,id.vars=c('decisionEventId','time','weight')),aes(x=time/3600,y=value,size=weight,colour=weight))+geom_point()+facet_wrap(~variable,scales='free_y')

  # Plot results
  #load(file=pp(matsim.shared,"parameterization/sensitivity.Rdata"))

  # What's up with the ALL or NOTHING choices?
  #nl.rep[decisionEventId %in% results[allSitePlugAlts <1e-5 | allSitePlugAlts>1-1e-5]$row]

  #save(nl.rep,file=pp('/Users/critter/Documents/matsim/pev/',out.dir,'/ITERS/it.0/clustered-attribute-values.Rdata'))
  list(nl.rep=nl.rep)
})

# Explore a few of the site/plug alternative utility coeffs
attrs.to.explore <- c('intercept','cost','chargerCapacity','distanceToActivity','remainingRange','remainingTravelDistanceInDay','nextTripTravelDistance','plannedDwellTime','isHomeActivityAndHomeCharger','isAvailable','isBEV')
attrs.to.explore <- c('cost','remainingRange','isAvailable')
attrs.to.explore <- c('cost','chargerCapacity')
attrs.to.explore <- c('intercept','cost')
attrs.code <- pp(attrs.to.explore,collapse='-')
attrs.to.explore.list <- alply(attrs.to.explore,1,function(ss){ unlist(param.coeffs.expanded$genericSitePlugTypeAlternative[[ss]]) })
param.combs <- data.table(setNames(expand.grid(attrs.to.explore.list),attrs.to.explore))

evaluate.coeff <- function(coeff.i){
  param.coeffs <- data.table(suppressWarnings(melt(param.combs[coeff.i],variable.name='attribute')))[,utility.function:='genericSitePlugTypeAlternative']
  final.coeffs <- join.on(param.coeffs.default,param.coeffs,c('utility.function','attribute'),included.prefix="new.")
  final.coeffs[!is.na(new.value),value:=new.value]
  return(rbindlist(sapply(nl.rep$decisionEventId,function(i){ data.table(row=i,t(evaluate.att(i,param.coeffs.to.use=final.coeffs,alts.from.beam=T))) }),fill=T))
}

suppressWarnings(rm('results'))
do.or.load(pp('/Users/critter/Documents/matsim/pev/',out.dir,'/ITERS/it.0/sensitivity-',attrs.code,'.Rdata'),function(){
  my.cat('Applying NL model across coefficient combinations....')
  results <- rbindlist(alply(1:nrow(param.combs),1,function(i){ data.table(coeff.row=i,evaluate.coeff(i)) },.parallel=T),fill=T)
  streval(pp('results[,yesAlt:=sum(',pp(grep('generic',names(results),value=T),collapse=','),',na.rm=T),by=c("coeff.row","row")]'))
  param.combs.dt <- copy(param.combs)
  param.combs.dt[,coeff.row:=1:nrow(param.combs.dt)]
  results <- join.on(results,param.combs.dt,'coeff.row','coeff.row')
  list(results=results)
})
results <- streval(pp('results[,list(',pp(c('coeff.row','row','yesAlt','tryChargingLater','continueSearchInLargerArea','abort',names(param.combs)),collapse=','),')]'))
results <- join.on(results,nl.rep,'row','decisionEventId',c('time','weight','isHomeActivity'))
results.m <- melt(results[isHomeActivity==0],measure.vars=c('yesAlt','tryChargingLater','continueSearchInLargerArea','abort'))
results.m[,log.odds:=log(value/(1-value))]
results.m[log.odds< -20,log.odds:=-20]
results.m[log.odds> 20,log.odds:=20]
results.m[,hour:=floor(time/3600)]
setkey(results.m,hour,variable)

my.cat('Plotting.....')
for(indep.var in attrs.to.explore){
  my.cat(indep.var)
  streval(pp('print(results[,mean(yesAlt),by="',indep.var,'"])'))
  streval(pp('p <- ggplot(results.m,aes(x=factor(',indep.var,'),y=log.odds,colour=variable)) + geom_boxplot(outlier.shape=".") + labs(x="',indep.var,' (',att.values.orig[attribute==indep.var]$units,')",y="Log Odds",title="',indep.var,'",colour="Alternative")'))
  ggsave(file=pp('/Users/critter/Documents/matsim/pev/',out.dir,"/ITERS/it.0/logodds-",indep.var,".png"),p,width=6.4,height=3.6)
  streval(pp('p <- ggplot(results.m,aes(x=time/3600,y=log.odds,colour=variable,size=weight)) + geom_point() + facet_wrap(~',indep.var,') + labs(x="Hour",y="Log Odds",title="',indep.var,'",colour="Alternative")'))
  ggsave(file=pp('/Users/critter/Documents/matsim/pev/',out.dir,"/ITERS/it.0/time-series-",indep.var,".png"),p,width=2*6.4,height=2*3.6)
  streval(pp('p <- ggplot(results.m,aes(x=hour,y=value,colour=variable)) + geom_bar(stat="identity",position="stack") + facet_wrap(~',indep.var,') + labs(x="Hour",y="Sum Probabilities",title="',indep.var,'",colour="Alternative")'))
  ggsave(file=pp('/Users/critter/Documents/matsim/pev/',out.dir,"/ITERS/it.0/profile-",indep.var,".png"),p,width=2*6.4,height=2*3.6)
}
#for(indep.var in attrs.to.explore){
  #streval(pp('print(results[intercept==2,mean(yesAlt),by="',indep.var,'"])'))
  #streval(pp('p <- ggplot(results.m[intercept==2],aes(x=factor(',indep.var,'),y=log.odds,colour=variable)) + geom_boxplot(outlier.shape=".") + labs(x="',indep.var,' (',att.values.orig[attribute==indep.var]$units,')",y="Log Odds",title="',indep.var,'",colour="Alternative")'))
  #ggsave(file=pp('/Users/critter/Documents/matsim/pev/',out.dir,"/ITERS/it.0/int2-logodds-",indep.var,".png"),p,width=6.4,height=3.6)
  #streval(pp('p <- ggplot(results.m[intercept==2],aes(x=time/3600,y=log.odds,colour=variable,size=weight)) + geom_point() + facet_wrap(~',indep.var,') + labs(x="Hour",y="Log Odds",title="',indep.var,'",colour="Alternative")'))
  #ggsave(file=pp('/Users/critter/Documents/matsim/pev/',out.dir,"/ITERS/it.0/int2-time-series-",indep.var,".png"),p,width=2*6.4,height=2*3.6)
  #streval(pp('p <- ggplot(results.m[intercept==2],aes(x=hour,y=value,colour=variable)) + geom_bar(stat="identity",position="stack") + facet_wrap(~',indep.var,') + labs(x="Hour",y="Sum Probabilities",title="',indep.var,'",colour="Alternative")'))
  #ggsave(file=pp('/Users/critter/Documents/matsim/pev/',out.dir,"/ITERS/it.0/int2-profile-",indep.var,".png"),p,width=2*6.4,height=2*3.6)
#}

# Which coeff.row is default
param.combs.temp <- copy(param.combs)
param.combs.temp[,row:=1:nrow(param.combs)]
def.row <- streval(pp('param.combs.temp[',pp(names(param.combs),'==',apply(param.combs,2,function(x){ sort(u(x))[2] }),collapse=' & '),']$row'))
summary(results[coeff.row== def.row]$yesAlt)
# Find some combinations that get us to around 3% and look
see.more <- results[,mean(yesAlt),by='coeff.row'][abs(V1-0.01)<0.0025]$coeff.row
param.combs.temp[row %in% see.more]



if(F){




system.time(results <- rbindlist(alply(sample(1:nrow(att.value.combs),10000),1,function(i){ data.table(row=i,t(evaluate.att(i))) },.parallel=T),fill=T))
make.dir(pp(matsim.shared,"parameterization/plots"))
results[,oneSite:=genericSitePlugTypeAlternative1]
results[,allSites:=rowSums(streval(pp("results[,list(",pp(grep("generic",names(results),value=T),collapse=","),")]")),na.rm=T)]
att.value.combs[,row:=1:nrow(att.value.combs)]
to.plot <- melt(join.on(results[,list(row,tryChargingLater,continueSearchInLargerArea,abort,oneSite,allSites)],att.value.combs,'row','row'),measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','oneSite','allSites'))
to.plot[,variable:=as.character(variable)]
to.plot[variable=="continueSearchInLargerArea",variable:="searchInLargerArea"]
to.plot[,variable:=factor(variable,levels=c('oneSite','allSites','searchInLargerArea','tryChargingLater','abort'))]

# First plots as OAT

for(indep.var in grep('row|variable|value',names(to.plot),value=T,invert=T)){
  if(indep.var == 'isBEV'){
    the.data <- to.plot
  }else{
    the.data <- to.plot[isBEV==1]
  }
  streval(pp('p <- ggplot(the.data,aes(x=factor(',indep.var,'),y=log(value/(1-value)),colour=variable)) + geom_boxplot(outlier.shape=".") + labs(x="',indep.var,' (',att.values.orig[attribute==indep.var]$units,')",y="Log Odds",title="',indep.var,'",colour="Alternative")'))
  ggsave(file=pp(matsim.shared,"parameterization/plots/2016-09-10-",indep.var,".png"),p,width=6.4,height=3.6)
}

ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(remainingRange),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(remainingTravelDistanceInDay),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(nextTripTravelDistance),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(plannedDwellTime),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(isHomeActivity),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(isBEV),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(cost),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(chargerCapacity),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(distanceToActivity),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(numChargers),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site','all.sites')),aes(x=factor(searchRadius),y=log(value/(1-value)),colour=variable)) + geom_boxplot() 

# prelim conclusions 2016-08-08
# cost insensitive, dist to activity insensitive, balance between yes/no should be struck first, then rebalance search farther vs try later
# continue search in larger area needs to be more sensitive to search distance

ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site')),aes(x=factor(),y=value,colour=variable)) +
       geom_boxplot() + 
       facet_grid(remainingTravelDistanceInDay~nextTripTravelDistance)
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site')),aes(x=factor(plannedDwellTime),y=value,colour=variable)) +
       geom_boxplot() + 
       facet_grid(isHomeActivity~isBEV)
ggplot(melt(to.plot,measure.vars=c('tryChargingLater','continueSearchInLargerArea','abort','one.site')),aes(x=factor(plannedDwellTime),y=value,colour=variable)) +
       geom_boxplot() + 
       facet_grid(isHomeActivity~isBEV)


# Run analysis from CE264 assignment 5

load.libraries('mlogit')

dat <- read.table('~/Dropbox/ucb/ce264/hw5/bats2000.dat',header=T,sep='\t')
names(dat) <- str_replace_all(names(dat),'_','.')
dat$cost.walk <- 0
dat$cost.bike <- 0
dat$dt.on.transit.tt <- dat$tt.dt 
dat$tt.dt <- dat$dt.on.transit.tt + dat$accTime.dt + dat$egrTime.dt + dat$ivtt.dt + dat$iWait.dt + dat$xWait.dt
dat$wt.on.transit.tt <- dat$tt.wt
dat$tt.wt <- dat$wt.on.transit.tt + dat$accTime.wt + dat$egrTime.wt + dat$walkTime.wt + dat$iWait.wt + dat$xWait.wt

datm <- mlogit.data(dat,choice='choice',shape='wide',id.var='obsID',varying=grep('^tt|cost|avail',names(dat)),alt.levels = c('da','sr','walk','bike','wt','dt'))

# MLogit Formulat has 3 parts separated by pipes |
# alt specific variables with generic coeffs
# indiv specific variables with separate coeffs for each alternative (minus 1)
# alt specific variables with alt specific coeffs

#mod <- mlogit(choice ~ alt + tt + cost, datm,nest=list(car=c('da','sr'),transit=c('wt','dt'),slow=c('walk','bike')))
#summary(mod)

# Note that the "iv" coefficients are the "elasticities" or the lambda parameters as documented by Train,
# these are the reciprical of the mu parameters as described in Ben-Akiva
#print(1/mod$coefficients[grep('^iv',names(mod$coefficients))])
#print(1/(mod$coefficients[grep('^iv',names(mod$coefficients))] + 1.96*summary(mod)$CoefTable[grep('^iv',names(mod$coefficients)),2]))
#print(1/(mod$coefficients[grep('^iv',names(mod$coefficients))] - 1.96*summary(mod)$CoefTable[grep('^iv',names(mod$coefficients)),2]))

mod2 <- mlogit(choice ~ alt + tt + cost, datm,nest=list(car=c('da','sr'),transit=c('wt','dt'),slow=c('walk','bike')),constPar=c('iv.car','iv.slow'))
summary(mod2)

#Coefficients :
                    #Estimate  Std. Error  t-value  Pr(>|t|)    
#sr:(intercept)   -1.11166422  0.01866867 -59.5471 < 2.2e-16 ***
#walk:(intercept) -3.67636880  0.05628581 -65.3161 < 2.2e-16 ***
#bike:(intercept) -3.88654143  0.06240645 -62.2779 < 2.2e-16 ***
#wt:(intercept)   -1.74214440  0.02875997 -60.5753 < 2.2e-16 ***
#dt:(intercept)   -2.31933500  0.07291977 -31.8067 < 2.2e-16 ***
#tt               -0.00027259  0.00004301  -6.3378 2.330e-10 ***
#cost             -0.02207202  0.00249685  -8.8399 < 2.2e-16 ***
#iv.transit        0.34005654  0.05615557   6.0556 1.399e-09 ***
#---
#Signif. codes:  0 ‘***’ 0.001 ‘**’ 0.01 ‘*’ 0.05 ‘.’ 0.1 ‘ ’ 1

#Log-Likelihood: -19503
#McFadden R^2:  0.011587 
#Likelihood ratio test : chisq = 457.28 (p.value = < 2.22e-16)


#preds <- predict(mod2,newdata=datm)

#tt <- c(67.7,67.7,433.74,74.7,0,0)
#cost <- c(1.8411,0.92055,0,0,0,0)
#tt <- c(74.7,67.7,0,67.7,433.74,0)
#cost <- c(0,1.8411,0,0.92055,0,0)
#utils <- c(0 + tt[1]*-0.00027259 + cost[1]*-0.02207202,
          #-1.11166422 + tt[2]*-0.00027259 + cost[2]*-0.02207202,
          #-3.67636880 + tt[3]*-0.00027259 + cost[3]*-0.02207202,
          #-3.88654143 + tt[4]*-0.00027259 + cost[4]*-0.02207202,
          #-1.74214440 + tt[5]*-0.00027259 + cost[5]*-0.02207202,
          #-2.31933500 + tt[6]*-0.00027259 + cost[6]*-0.02207202)
#names(utils) <- colnames(preds)
#mus <- 1/c(rep(1,4),0.34005654,0.34005654)
#names(mus) <- colnames(preds)

## Look at the data
#h(datm)[match(names(utils) ,datm$alt[1:6]),]

#nest.i <- grep('wt|dt',names(utils),invert=F)
#nonnest.i <- grep('wt|dt',names(utils),invert=T)

#nest.sum <- sum(exp(utils[nest.i]*mus[5]))^(1/mus[5])
#non.nest.sums <- exp(utils[nonnest.i])

#p.n.given.m <- rep(1,length(utils))
#names(p.n.given.m) <- names(utils)
#p.n.given.m[nest.i] <- exp(utils[nest.i]*mus[5])/sum(exp(utils[nest.i]*mus[5]))
#p.m <- c(non.nest.sums,nest.sum)/sum(c(non.nest.sums,nest.sum))
#p.m <- c(p.m,tail(p.m,1))
#print(p.n.given.m*p.m)


##
#<nestedLogit name="top">
	#<elasticity>1</elasticity>
	#<nestedLogit name="alternative1">
    #<elasticity>1</elasticity>
    #<utility>
      #<param name="intercept" type="INTERCEPT">1.0</param>
      #<param name="time" type="MULTIPLIER">1.0</param>
      #<param name="cost" type="MULTIPLIER">1.0</param>
    #</utility>
	#</nestedLogit>
	#<nestedLogit name="alternative2">
    #<elasticity>1</elasticity>
    #<utility>
      #<param name="intercept" type="INTERCEPT">0.0</param>
      #<param name="time" type="MULTIPLIER">1.0</param>
      #<param name="cost" type="MULTIPLIER">1.0</param>
    #</utility>
	#</nestedLogit>
	#<nestedLogit name="nest1">
    #<elasticity>0.5</elasticity>
    #<nestedLogit name="alternative3">
      #<elasticity>0.5</elasticity>
      #<utility>
        #<param name="intercept" type="INTERCEPT">-1.0</param>
        #<param name="time" type="MULTIPLIER">1.0</param>
        #<param name="cost" type="MULTIPLIER">1.0</param>
      #</utility>
    #</nestedLogit>
    #<nestedLogit name="alternative4">
      #<elasticity>0.5</elasticity>
      #<utility>
        #<param name="intercept" type="INTERCEPT">-2.0</param>
        #<param name="time" type="MULTIPLIER">1.0</param>
        #<param name="cost" type="MULTIPLIER">1.0</param>
      #</utility>
    #</nestedLogit>
	#</nestedLogit>
#</nestedLogit>

}
