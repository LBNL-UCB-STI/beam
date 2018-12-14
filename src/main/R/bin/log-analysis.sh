#!/usr/bin/env bash
library(colinmisc)
grep -n SUCCESS /Users/critter/Downloads/logs/batch1.log
head -1350120 batch1.log > base.log

echo "tick,id" > no-alts.csv
grep "no alternatives" console.log | sed 's/.*Tick://' | sed 's/ State.*PersonAgent:/,/' | sed 's/ Erroring:.*//' | grep -v "no alt" >> no-alts.csv

ids <- data.table(read.csv('~/Documents/beam/beam-output/1k_2017-10-03_15-59-09/no-alts.csv'))
ids[,noalt:=T]

plans <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/production/sfbay-1k/population.csv'))

load('')

plans <- join.on(plans,ids,'id','id')
plans[is.na(noalt),noalt:=F]
plans[end.time<0,end.time:=30*3600]

ggplot(plans[type=='Home'],aes(x=x,y=y,colour=tick/3600))+geom_point()+facet_wrap(~noalt)

3-0
