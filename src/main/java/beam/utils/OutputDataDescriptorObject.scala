package beam.utils

import beam.sim.OutputDataDescription

import java.util

/**
  * @author Dmitry Openkov
  */
object OutputDataDescriptorObject {

  /**
    * It allows to create an instance of OutputDataDescriptor for beam documentation generation
    * @param className the class name
    * @param outputFile the output file name
    * @param iterationLevel set to true in case the output file is generated for a single iteration, false if the file
    *                       is generated on the whole simulation leve.
    * @param table a table containing column names and their descriptions. Each line describes a column. Separator is |
    * @return OutputDataDescriptor
    */
  def apply(className: String, outputFile: String, iterationLevel: Boolean = false)(
    table: String
  ): OutputDataDescriptor = {
    val iterationOrRootFile = if (iterationLevel) s"/ITERS/it.0/0.$outputFile" else s"/$outputFile"
    val lines = table.split('\n')
    val descriptions = lines
      .map(_.trim())
      .filter(_.nonEmpty) // remove empty lines
      .map(_.split('|').map(_.trim()).filter(_.nonEmpty)) // extract field name and description
      .map { fieldDescriptionArray =>
        require(
          fieldDescriptionArray.length == 2,
          s"$className, $outputFile description has a table line that contains wrong number of elements"
        )
        OutputDataDescription(
          className,
          iterationOrRootFile,
          field = fieldDescriptionArray(0),
          description = fieldDescriptionArray(1)
        )
      }

    _ => util.Arrays.asList(descriptions: _*)
  }

}
