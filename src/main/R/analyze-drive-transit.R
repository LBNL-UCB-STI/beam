

ev <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/output/test/sf-light-1k_2018-05-14_17-26-50/ITERS/it.0/0.events.csv'))

peeps <- ev[type=='departure' & legMode=='drive_transit']$person

ev[person%in%peeps & type=='arrival' & legMode != 'drive_transit']

ev[!substr(person,1,4)%in%c('ride','Tran') & (type=='departure' | type=='arrival'),.(deps=legMode[seq(1,length(legMode),by=2)],arrs=legMode[seq(2,length(legMode),by=2)]), by='person']

peeps <- ev[!substr(person,1,4)%in%c('ride','Tran') & (type=='departure' | type=='arrival'),.(deps=legMode[seq(1,length(legMode),by=2)],arrs=legMode[seq(2,length(legMode),by=2)]), by='person'][deps!=arrs]$person

ev[person%in%peeps[1]]
