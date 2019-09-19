package beam.utils.scenario

sealed trait InputType {

  def toFileExt: String = this match {
    case InputType.Parquet => "parquet"
    case InputType.CSV     => "csv"
  }
}

object InputType {
  case object Parquet extends InputType
  case object CSV extends InputType

  def apply(inputType: String): InputType = {
    inputType match {
      case "csv"     => CSV
      case "parquet" => Parquet
      case _         => throw new IllegalStateException(s"There is no map from type '$inputType to `InputType`")
    }
  }
}
