# bash commands to dump and tar tabular data from shapefiles into csv in batch:
# ls /Users/critter/Dropbox/ucb/vto/smart-mobility/final-results/mep/*/*/mep.shp | sed 's/\.shp//g' | xargs -I % /Users/critter/Dropbox/abc/ternPOP/ternpop/bin/shp2csv.R -s %
# cd /Users/critter/Dropbox/ucb/vto/smart-mobility/final-results/mep/
# find ./ -name "mep.csv" -not -path "*/old/*" | tar -czf mep-dec13.tgz -T -

library(stringr)

setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

mep.mode.colors <- data.table(rbind(mode.colors,data.frame(key='Bike',color='peach',color.hex='#f2b272')))
mep.mode.colors[,name:=c('tnc','drive','walk','transit','','bike')]

scen.codes <- c('Base0'='2010_base',
                'Base2'='2025_base_short_bau',
                'Base3'='2025_base_short_vto',
                'Base5'='2040_base_long_bau',
                'Base6'='2040_base_long_vto',
                'A2'='2025_a_bau',
                'A3'='2025_a_vto',
                'B5'='2040_b_bau',
                'B6'='2040_b_vto',
                'C5'='2040_c_bau',
                'C6'='2040_c_vto')

mep.dir <- '/Users/critter/Dropbox/ucb/vto/smart-mobility/final-results/mep/'

mep.subs <- grep('old|heatmap',list.dirs(mep.dir,recursive=F,full.names=F),value=T,invert=T)

mep.sub<-mep.subs[1]

mep <- list()
for(mep.sub in mep.subs){
  the.file <- grep('overall_mep.csv',list.files(pp(mep.dir,mep.sub)),value=T)
  the.scen <- str_split(mep.sub,'beam_dec13_')[[1]][2]

  tmp <- data.table(read.csv(pp(mep.dir,mep.sub,'/',the.file)))
  tmp[,scen:=names(scen.codes)[scen.codes == the.scen]]
  mep[[length(mep)+1]] <- tmp
}
mep <- na.omit(rbindlist(mep))
mep[,col:=mep.mode.colors$color.hex[match(type,mep.mode.colors$name)]]
mep[,type.polished:=mep.mode.colors$key[match(type,mep.mode.colors$name)]]
mep[,scen:=factor(scen,c('Base0','Base2','Base3','A2','A3','Base5','Base6','B5','B6','C5','C6'))]

ggplot(mep[type!='mep' & !scen %in% c('Base2','Base3','Base4','Base5')],aes(x=scen,y=overall_mep,fill=type))+geom_bar(stat='identity')+theme_bw()+
  scale_fill_manual(values=as.character(mep.mode.colors$color.hex[match(sort(u(mep[type!='mep']$type)),mep.mode.colors$name)]),labels=as.character(mep.mode.colors$key[match(sort(u(mep[type!='mep']$type)),mep.mode.colors$name)])) + labs(x='',y='Mobility Energy Productivity',fill='Mode')

ggplot(mep[type!='mep'],aes(x=scen,y=overall_mep,fill=type))+geom_bar(stat='identity')+theme_bw()+
  scale_fill_manual(values=as.character(mep.mode.colors$color.hex[match(sort(u(mep[type!='mep']$type)),mep.mode.colors$name)]),labels=as.character(mep.mode.colors$key[match(sort(u(mep[type!='mep']$type)),mep.mode.colors$name)])) + labs(x='',y='Mobility Energy Productivity',fill='Mode')
  

