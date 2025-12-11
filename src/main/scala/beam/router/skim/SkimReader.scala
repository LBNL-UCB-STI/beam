package beam.router.skim

import com.typesafe.scalalogging.Logger

import java.io.{BufferedReader, Closeable}

trait SkimReader[Key, Value] extends Closeable {
  def aggregatedSkimsFilePath: String
  def logger: Logger

  /**
    * Reads aggregated skims from the configured file path
    * @return Map of keys to values, or empty map if file doesn't exist or reading fails
    */
  def readAggregatedSkims: Map[Key, Value]

  /**
    * Reads skims from the provided BufferedReader
    * @param reader the BufferedReader to read from
    * @return Map of keys to values, or empty map if reading fails
    */
  def readSkims(reader: BufferedReader): Map[Key, Value]

}
