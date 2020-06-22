# Sampling routes for analysis
#### Prepare the script to generate 24 hours data for you area.
The easiest way is to make a copy of [austin_24hours.sh](austin_24hours.sh) and adjust it accordingly regarding the params for the study area:
 - INPUT_FILE
 - BOUNDING_BOX

#### Run the script to get the sampled routes
The data will be put into the output folder. An example of generated output:
![generated_output](https://user-images.githubusercontent.com/5107562/82683406-2e4e1100-9c7b-11ea-9625-e11684fd50f8.png)

There will be 24 files, the one for each hour range in seconds.

#### Extract the links from generated output
Run `extract_google_links.py` python script to extract Google links to separate files:
```bash
python extract_google_links.py \
--inputFolder="output/detroit/detroit-200k-flowCapacityFactor-0.1__2020-05-19_12-56-04_ayp/ITERS/it.10"
``` 
The output folder will look like the following:
![extract_google_links](https://user-images.githubusercontent.com/5107562/82683902-f7c4c600-9c7b-11ea-9047-0d57fc110f60.png)


### Run [google-route-reader](https://github.com/REASY/google-route-reader) using generated *_links.txt files to get the routes