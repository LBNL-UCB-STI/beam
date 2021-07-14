package beam.agentsim.infrastructure.geozone.aggregation

sealed trait ValueAggregator {
  def aggregate(values: Seq[ParkingEntryValues]): Seq[ParkingEntryValues]
}

object ValueAggregator {

  object StallSummationAndFeeWeightAvg extends ValueAggregator {

    override def aggregate(values: Seq[ParkingEntryValues]): Seq[ParkingEntryValues] = {
      val stallTotalSum = values.map(_.numStalls).sum
      val stallWeightedSum = values.map(v => v.numStalls * v.feeInCents).sum
      val feeValue = stallWeightedSum / stallTotalSum
      Seq(ParkingEntryValues(stallTotalSum, feeValue))
    }
  }

}
