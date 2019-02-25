
load.libraries(c('stringr'))

samp.dir <-  '/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples'

hh <- data.table(read.csv(pp(samp.dir,'/hh-members.csv'),header=F,stringsAsFactors=F))
hh[,ndash:=str_count(V1,'-')]

hh.inc <- hh[ndash==0]
hh.inc[,income:=as.numeric(substr(as.character(V1),3,nchar(as.character(V1))))]
hh.inc[,V1:=NULL]
hh.veh <- hh[ndash==1]
hh.id <- hh[ndash==2]
hh.id[,income:=hh.inc$income]
hh <- hh.id

hh2 <- data.table(read.csv(pp(samp.dir,'/hh-id.csv'),header=F))
hh2[,x:=data.table(read.csv(pp(samp.dir,'/hh-x.csv'),header=F))$V1]
hh2[,y:=data.table(read.csv(pp(samp.dir,'/hh-y.csv'),header=F))$V1]

hh <- join.on(hh,hh2,'V1','V1')
hh[,':='(id=V1,V1=NULL)]
hh[,':='(ndash=NULL)]

vot <- data.table(read.csv(pp(samp.dir,'/vott-id.csv'),header=F))
vot[,vot:=data.table(read.csv(pp(samp.dir,'/vott-value.csv'),header=F))$V1]
vot[,':='(id=V1,V1=NULL)]

load("/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/population.Rdata",verb=T)
pop <- copy(df)
rm('df')
pop <- join.on(pop,vot,'id','id')
if(!'hh'%in%names(pop)){
  pop[,hh:=unlist(lapply(str_split(id,'-'),function(ll){ pp(head(ll,-1),collapse='-') }))]
}
pop <- join.on(pop,hh,'hh','id',included.prefix='hh.')
save(pop,hh,file="/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/pop-full.Rdata",verb=T)

setkey(pop,id)
pop <- u(pop)
pop[hh.income<1000,hh.income:=1000]
pop[vot<1,vot:=1]
pop[,income.bin.10k:=floor(hh.income/10000)*10]
pop[income.bin.10k>=100 & income.bin.10k<150,income.bin.10k:=100]
pop[income.bin.10k>=150 & income.bin.10k<200,income.bin.10k:=150]
pop[income.bin.10k>=200,income.bin.10k:=200]

ggplot(pop,aes(x= factor(income.bin.10k),y=vot))+ geom_boxplot()+labs(x="Annual Household Income ($1000)",y="Value of Travel Time ($/hr)",title="SF Bay Area Population Value of Travel Time")
dev.new()
ggplot(pop,aes(x= hh.income/1000))+ geom_histogram()+labs(x="Annual Household Income ($1000)",y="Frequency",title="SF Bay Area Population Household Income")
