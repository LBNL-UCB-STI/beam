require(XML)
veh.types <- data.table(read.csv(pp(matsim.shared,"model-inputs/development/vehicle-types.csv")))

for(sample.type in c('1_agent','3_agents','10_agents','10k','100k')){
  data <- xmlParse(pp(matsim.shared,"model-inputs/development/mtc_plans_sample_",sample.type,".xml"))
  plans <- xmlToList(data)

  # Uniform distribution of vehicle types
  person.veh <- list()
  for(person.i in 1:length(plans)){
    person.veh[[person.i]] <- data.table(personId=as.numeric(plans[[person.i]]$.attrs['id']),
                       vehicleTypeId=sample(nrow(veh.types),1))
  }
  person.veh <- rbindlist(person.veh)

  write.csv(person.veh,pp(matsim.shared,"model-inputs/development/person-vehicle-types-",sample.type,".csv"),row.names=F)
}

