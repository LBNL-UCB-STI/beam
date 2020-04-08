package beam.agentsim.infrastructure.h3

@SerialVersionUID(1L)
case class H3InvalidOutputSizeException(outputSize: Int, coordinatesSize: Int) extends RuntimeException(
  s"Number of expected buckets ($outputSize) cannot be bigger than number of coordinates ($coordinatesSize)."
)
