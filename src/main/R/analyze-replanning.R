
options(gsubfn.engine = "R")
load.libraries(c('sqldf','R.utils'))
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

run.names <- c('noisy'='sf-light-25k_2018-02-13_08-48-21')

update.exp.plans <- T
update.events <- T
temp.dir <- tempdir()
all.tmp <- list()
all.events <- list()
if(update.exp.plans & exists('exp.plans'))rm('exp.plans')
if(update.events & exists('ev'))rm('ev')
run.name <- run.names[1]
for(run.name in run.names){
  run.code <- names(run.names)[which(run.name==run.names)]
  run.dir <- pp('/Users/critter/Documents/beam/beam-output/',run.name)

  iter.dir <- list.dirs(pp(run.dir,'/ITERS'),recursive=F)[1]
  for(iter.dir in list.dirs(pp(run.dir,'/ITERS'),recursive=F)){
    split.parts <- str_split(list.files(iter.dir)[1],'\\.')[[1]]
    iter.num <- split.parts[1]
    the.file <- pp(iter.dir,'/',iter.num,'.population.csv.gz')
    the.file.Rdata <- pp(iter.dir,'/',iter.num,'.population.Rdata')
    if(update.exp.plans & file.exists(the.file)){
      if(exists('dt'))rm(dt)
      do.or.load(the.file.Rdata,function(){
        dt <- data.table(read.csv(gzfile(the.file),header=F))
        dt[,run:=run.code]
        dt[,iter:=as.numeric(iter.num)]
        return(list(dt=dt))
      })
      all.tmp[[length(all.tmp)+1]] <- dt 
    }
    the.file <- pp(iter.dir,'/',iter.num,'.events.csv')
    if(update.events & file.exists(the.file)){
      dt <- csv2rdata(the.file)
      dt[,run:=run.code]
      dt[,iter:=as.numeric(iter.num)]
      dt[,score:=as.numeric(score)]
      all.events[[length(all.events)+1]] <- dt
    }
  }
}
if(update.exp.plans) exp.plans <- rbindlist(all.tmp)
if(update.events) ev <- rbindlist(all.events)
rm(all.tmp,all.events,dt)
exp.plans[,score:=planScore+chargingSequenceScore]

sum.exp <- exp.plans[planElementType=='plan',list(max.score=max(score),min.score=min(score),selected.score=score[isSelectedEVDailyPlan=='true'],n.scores=length(score)),by=c('run','iter','personId')][,list(avg.max.score=mean(max.score),avg.min.score=mean(min.score),avg.selected.score=mean(selected.score),n.scores=mean(n.scores)),by=c('run','iter')]
setkey(sum.exp,run,iter)

#cast(melt(sum.exp,id.vars=c('run','iter')),iter ~ variable * run)

#ggplot(exp.plans[planElementType=='plan' & isSelectedEVDailyPlan=='true'],aes(x=factor(iter),y=score))+geom_boxplot()
to.plot <- melt(sum.exp,id.vars=c('run','iter'),measure.vars=c('avg.max.score','avg.min.score','avg.selected.score'))
to.plot[,variable:=revalue(variable,c(avg.max.score="Max",avg.min.score="Min",avg.selected.score="Selected"))]
to.plot[,run:=revalue(run,c('no-time-mutation'="Without Time Mutation",'time-mutation'="With Time Mutation"))]
ggplot(to.plot,aes(x=iter,y=value,colour=variable))+geom_line()+facet_wrap(~run)+labs(title="Progression of Average Agent Score Distribution",x="Iteration",y="Score")

#sum.ev <- ev[type%in%c('ParkingScoreEvent','ChargingCostScoreEvent','RangeAnxietyScoreEvent','LegTravelTimeScoreEvent'),mean(score),by='type']
#sum.ev <- ev[type%in%c('ParkingScoreEvent','ChargingCostScoreEvent','RangeAnxietyScoreEvent','LegTravelTimeScoreEvent'),list(score=sum(score)),by=c('person','iter')][,list(score=mean(score)),by='iter']
#setkey(sum.ev,iter)


# Analyze the composition of charging strategies used

sum.strats <- ev[!is.na(strategyId),list(frac1=sum(strategyId==1)/length(strategyId),frac2=sum(strategyId==2)/length(strategyId),frac3=sum(strategyId==3)/length(strategyId),frac4=sum(strategyId==4)/length(strategyId)),by=c('run','iter','person')][,list(frac1=mean(frac1),frac2=mean(frac2),frac3=mean(frac3),frac4=mean(frac4)),by=c('run','iter')]
setkey(sum.strats,run,iter)
sum.strats[run=='four']

# How often is en route charging being chosen
ev[iter==max(ev$iter) & type=='DepartureChargingDecisionEvent',list(n=sum(choice=='enRoute')),by=c('run','iter')][,list(n=mean(n)),by='run']
# distribution of choices
ev[iter==max(ev$iter) & !is.na(choice) & choice!='',list(n=length(person)),by=c('run','choice')]
# choice at end of day
last.choice <- ev[type=='ArrivalChargingDecisionEvent',list(choice=tail(choice,1)),by=c('run','iter','person')]
last.choice <- last.choice[,list(n.charge=sum(choice=='charge'),n.tot=length(choice)),by=c('run','iter')]
setkey(last.choice,iter,run)

# Full run just using "balanced" strategy
ev[type=='DepartureChargingDecisionEvent',list(n=sum(choice=='enRoute'),n.tot=length(choice)),by=c('iter')]
ev[!is.na(choice) & choice!='',list(n=length(person)),by=c('iter','choice')]

# Look into strandings
iter.last <- u(ev[iter==max(ev$iter) & choice=='stranded']$person)
for(the.iter in sort(u(ev$iter))){
  this.iter <- u(ev[iter==the.iter & choice=='stranded']$person)
  my.cat(pp(the.iter,' --- ',length(this.iter),' --- ',sum(iter.last %in% this.iter)/length(iter.last)))
}
# So strandings are improved over time and it's safe to say that those still stranded by the end are the important ones to address
# iter 0 - 0.9817629
# iter 10 - 0.9805471
# iter 20 - 0.9665653

init.soc <- ev[iter==max(ev$iter) & choice=='stranded',list(soc=head(soc,1)),by='person']

load("/Users/critter/Documents/matsim/input/run0-201-leg-data.Rdata",verb=T)
hist(legs[person%in%iter.30,list(trip.tot=sum(miles)),by='person']$trip.tot)
dev.new();hist(legs[,list(trip.tot=sum(miles)),by='person']$trip.tot)

atts <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/person-attributes-from-reg.csv'))
atts[personId%in%iter.30, simulationStartSocFraction:=1]
write.csv(atts,'/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/person-attributes-from-reg.csv',row.names=T)
