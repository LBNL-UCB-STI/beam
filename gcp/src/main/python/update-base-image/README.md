In order to set up the image updates you need to create the following functions:

* createImage
* createSnapshot
* delete-snapshot
* updateEnvVarsForProvidedFunctionNames
* update-base-image (set to a 540 second timeout - and read full notes first)

For `update-base-image` you need the following environment variables:

* BRANCHES = `master develop freight-develop hl/gemini-develop gemini-develop`
* SUBMODULES = `production/newyork production/sfbay production/austin`

And to have it run regularly you must first create a topic with a default subscription. Now, you must create the function with an EventArc trigger (only possible during creation) set up as:
 
* Event Provider: `Cloud Pub/Sub`
* Event Type: `google.cloud.pubsub.topic.v1.messagePublished`
* Topic: **(The one previously created)**

Then create a Cloud Scheduler job with the following:

* Frequency of `0 * */4 * *`
* Target Type: `Pub/Sub`
* Topic: **(The one previously created)**
* Message Body: `Run` **(although it can be anything - as it is not used)**
