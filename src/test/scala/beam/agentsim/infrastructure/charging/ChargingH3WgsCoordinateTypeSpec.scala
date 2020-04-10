package beam.agentsim.infrastructure.charging

import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import org.scalactic.source
import org.scalatest.{Matchers, WordSpec}

class ChargingH3WgsCoordinateTypeSpec extends WordSpec with Matchers {
  "ChargingPointType" when {
    "formal charging point types" when {
      "given string names of built-in charging point types" should {
        "construct that charging point type" in {
          for {
            expectedChargingPointType <- ChargingPointType.AllFormalChargingPointTypes
            chargingPointTypeString = expectedChargingPointType.toString
          } {
            ChargingPointType(chargingPointTypeString) match {
              case None =>
                fail(s"name $chargingPointTypeString did not map to a ChargingPointType object")
              case Some(chargingPointType) =>
                chargingPointType should equal(expectedChargingPointType)
            }
          }
        }
      }
    }
    "custom charging point types" when {
      "given a string describing a valid custom ChargingPointType with correct formatting" should {
        "construct that charging point type" in {
          val input = "test(250|DC)"
          ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(input) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.id should equal("test")
              result.installedCapacity should equal(250.0)
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given a string with valid CustomChargingPoint data but extra spaces" should {
        "still correctly parse and construct that ChargingPointType" in {
          val input = "test ( 250 | DC )"
          ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(input) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.id should equal("test")
              result.installedCapacity should equal(250.0)
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given strings with invalid installed capacity values" should {
        "throw an exception" in {
          val twoPeriods = "test ( 250.. | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(twoPeriods)
          val hasLetters = "test ( 25a0 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(hasLetters)
          val comma = "test ( 250,0 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(comma)
          val negativeValue = "test ( -250 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(
            negativeValue
          )

        }
      }
      "given strings with varying character case for electricCurrentType" should {
        "still correctly parse and construct that ChargingPointType" in {
          val bothUpper = "test ( 250 | AC )"
          val lowerFirst = "test ( 250 | dC )"
          val lowerSecond = "test ( 250 | Ac )"
          val bothLower = "test ( 250 | dc )"
          ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(bothUpper) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.AC)
          }
          ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(lowerFirst) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
          ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(lowerSecond) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.AC)
          }
          ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(bothLower) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given strings with invalid electricCurrentType values" should {
        "throw an exception" in {
          val badLetters = "test ( 250 | AB )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(badLetters)
          val spaceBetweenLetters = "test ( 250 | A C )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(
            spaceBetweenLetters
          )
          val containsValidButTooLong = "test ( 25a0 | aaaDCaaa )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(
            containsValidButTooLong
          )
          val flipped = "test ( DC | 1234 )"
          an[IllegalArgumentException] should be thrownBy ChargingH3WgsCoordinateTypeSpec.expectsACustomChargingPoint(flipped)
        }
      }
    }
  }
}

object ChargingH3WgsCoordinateTypeSpec extends org.scalatest.Assertions {

  /**
    * helper test function which handles the common parsing failures
    * @param input a string representation of a CustomChargingPoint
    * @param pos required for calling "fail" here, an implicit that tracks the call point of an error
    * @return either an error string, or a CustomChargingPoint
    */
  def expectsACustomChargingPoint(input: String)(implicit pos: source.Position): Either[String, CustomChargingPoint] = {
    ChargingPointType(input) match {
      case None =>
        Left { s"input '$input' failed to parse" }
      case Some(chargingPointType) =>
        chargingPointType match {
          case result: CustomChargingPoint =>
            Right { result }
          case _ =>
            Left { s"a CustomChargingPoint was not parsed from provided string description '$input'" }
        }
    }
  }
}
