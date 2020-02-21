package beam.utils.data.synthpop

trait WorkedDurationGenerator {

  /** Gives back the next worked duration
    * @param   rangeWhenLeftHome   The range in seconds, in 24 hours, when a person left a home
    * @return Worked duration in seconds
    */
  def next(rangeWhenLeftHome: Range): Int
}

class WorkedDurationGeneratorImpl(pathToCsv: String, randomSeed: Int) extends WorkedDurationGenerator {

  /** Gives back the next worked duration
    *
    * @param   rangeWhenLeftHome The range in seconds, in 24 hours, when a person left a home
    * @return Worked duration in seconds
    */
  override def next(rangeWhenLeftHome: Range): Int = {
    // TODO Use JointDistribution from Rajnikant
    (8.5 * 3600).toInt
  }
}
