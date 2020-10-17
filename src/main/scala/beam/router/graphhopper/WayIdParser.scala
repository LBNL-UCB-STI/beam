package beam.router.graphhopper

import java.nio.ByteOrder
import java.util

import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ev.{
  BooleanEncodedValue,
  EncodedValue,
  EncodedValueLookup,
  IntEncodedValue,
  SimpleBooleanEncodedValue,
  UnsignedIntEncodedValue
}
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.util.parsers.TagParser
import com.graphhopper.storage.IntsRef
import com.graphhopper.util.{BitUtil, EdgeIterator}

class WayIdParser extends TagParser {
  // First 31 bits of Long
  private val encoderLow: IntEncodedValue = new UnsignedIntEncodedValue("way_id_low", 31, false)
  // Flag whether it was converted from a negative to a positive number
  private val encoderLowFlag: BooleanEncodedValue = new SimpleBooleanEncodedValue("way_id_low_flag", false)

  // Next 31 bits of Long
  private val encoderHigh: IntEncodedValue = new UnsignedIntEncodedValue("way_id_high", 31, false)
  // Flag whether it was converted from a negative to a positive number
  private val encoderHighFlag: BooleanEncodedValue = new SimpleBooleanEncodedValue("way_id_high_flag", false)

  override def createEncodedValues(
    lookup: EncodedValueLookup,
    registerNewEncodedValue: util.List[EncodedValue]
  ): Unit = {
    registerNewEncodedValue.add(encoderLow)
    registerNewEncodedValue.add(encoderLowFlag)
    registerNewEncodedValue.add(encoderHigh)
    registerNewEncodedValue.add(encoderHighFlag)
  }

  override def handleWayTags(edgeFlags: IntsRef, way: ReaderWay, ferry: Boolean, relationFlags: IntsRef): IntsRef = {
    val wayId = way.getId
    val low = WayIdParser.byteUtil.getIntLow(wayId)
    setInt(encoderLow, encoderLowFlag, edgeFlags, low)

    val high = WayIdParser.byteUtil.getIntHigh(wayId)
    setInt(encoderHigh, encoderHighFlag, edgeFlags, high)
    edgeFlags
  }

  private def setInt(
    encoder: IntEncodedValue,
    encoderFlag: BooleanEncodedValue,
    edgeFlags: IntsRef,
    value: Int
  ): Unit = {
    if (value < 0) {
      encoder.setInt(false, edgeFlags, -1 * value)
      encoderFlag.setBool(false, edgeFlags, true)
    } else {
      encoder.setInt(false, edgeFlags, value)
      encoderFlag.setBool(false, edgeFlags, false)
    }
  }
}

object WayIdParser {
  val byteUtil: BitUtil = BitUtil.get(ByteOrder.LITTLE_ENDIAN)
}

class EdgeIdToWayIdConvertor(encodingManager: EncodingManager) {
  private val encoderLow: IntEncodedValue = encodingManager.getIntEncodedValue("way_id_low")
  private val encoderLowFlag: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("way_id_low_flag")
  private val encoderHigh: IntEncodedValue = encodingManager.getIntEncodedValue("way_id_high")
  private val encoderHighFlag: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("way_id_high_flag")

  def getWayId(edgeIterator: EdgeIterator): Long = {
    val low = getInt(encoderLow, encoderLowFlag, edgeIterator)
    val high = getInt(encoderHigh, encoderHighFlag, edgeIterator)
    WayIdParser.byteUtil.toLong(low, high)
  }

  private def getInt(encoder: IntEncodedValue, encoderFlag: BooleanEncodedValue, edgeIterator: EdgeIterator): Int = {
    val value = edgeIterator.get(encoder)
    val wasNegative = edgeIterator.get(encoderFlag)
    if (wasNegative) {
      -1 * value
    } else {
      value
    }
  }
}
