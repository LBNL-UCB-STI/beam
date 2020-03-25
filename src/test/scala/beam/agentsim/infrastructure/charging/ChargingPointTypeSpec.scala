package beam.agentsim.infrastructure.charging

import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import org.scalactic.source
import org.scalatest.{Matchers, WordSpec}

class ChargingPointTypeSpec extends WordSpec with Matchers {
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
          ChargingPointTypeSpec.expectsACustomChargingPoint(input) match {
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
          ChargingPointTypeSpec.expectsACustomChargingPoint(input) match {
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
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(twoPeriods)
          val hasLetters = "test ( 25a0 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(hasLetters)
          val comma = "test ( 250,0 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(comma)
          val negativeValue = "test ( -250 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(
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
          ChargingPointTypeSpec.expectsACustomChargingPoint(bothUpper) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.AC)
          }
          ChargingPointTypeSpec.expectsACustomChargingPoint(lowerFirst) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
          ChargingPointTypeSpec.expectsACustomChargingPoint(lowerSecond) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.AC)
          }
          ChargingPointTypeSpec.expectsACustomChargingPoint(bothLower) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given strings with invalid electricCurrentType values" should {
        "throw an exception" in {
          val badLetters = "test ( 250 | AB )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(badLetters)
          val spaceBetweenLetters = "test ( 250 | A C )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(
            spaceBetweenLetters
          )
          val containsValidButTooLong = "test ( 25a0 | aaaDCaaa )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(
            containsValidButTooLong
          )
          val flipped = "test ( DC | 1234 )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(flipped)
        }
      }
    }
  }
}

object ChargingPointTypeSpec extends org.scalatest.Assertions {

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
