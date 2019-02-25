
grep "object id" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/populationAttributes.xml | sed 's/.*id="//' | sed 's/">*//'  > vott-id.csv
grep "valueOfTime" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/populationAttributes.xml | sed 's/.*Double">//' | sed 's/<\/attribute>*//'  > vott-value.csv

grep "object id" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/householdAttributes.xml | sed 's/.*id="//' | sed 's/">*//'  > hh-id.csv
grep "homecoordx" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/householdAttributes.xml | sed 's/.*Double">//' | sed 's/<\/attribute>*//'  > hh-x.csv
grep "homecoordy" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/householdAttributes.xml | sed 's/.*Double">//' | sed 's/<\/attribute>*//'  > hh-y.csv
grep "housingtype" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/householdAttributes.xml | sed 's/.*Double">//' | sed 's/<\/attribute>*//'  > hh-type.csv

# following requires some editing
grep "<household\|refId\|\.0" /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/households.xml | sed 's/.*refId="//' | sed 's/"\/>//' | sed 's/.*household id="//' | sed 's/">.*//' > hh-members.csv

# move them all back up

mv vott*.csv /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/
mv hh*.csv /Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/
