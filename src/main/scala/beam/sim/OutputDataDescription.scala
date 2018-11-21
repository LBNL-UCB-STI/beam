package beam.sim

/**
  * A class that describes the fields in every output file.
  * @param className name of the class that generates it
  * @param outputFile relative path of the file in the output folder
  * @param field field to be described
  * @param description description of the field
  */
case class OutputDataDescription(className: String, outputFile: String, field: String, description: String)
