package beam.utils

import javax.management.{
  InstanceNotFoundException,
  MBeanException,
  MBeanServer,
  MalformedObjectNameException,
  ObjectName,
  ReflectionException
}

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.IOException
import java.lang.management.ManagementFactory

object VMInfoCollector {
  private val DIAGNOSTIC_COMMAND_MBEAN_OBJECT_NAME = "com.sun.management:type=DiagnosticCommand"

  def apply(): VMInfoCollector = {
    try {
      val objectName = new ObjectName(DIAGNOSTIC_COMMAND_MBEAN_OBJECT_NAME)
      new VMInfoCollector(objectName)
    } catch {
      case _: MalformedObjectNameException =>
        throw new RuntimeException(
          "Unable to create an ObjectName and so unable to create instance of VirtualMachineDiagnostics"
        )
    }
  }
}

class VMInfoCollector(val objectName: ObjectName) {
  private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer

  def gcRun(): Unit = invoke("gcRun")

  def dumpHeap(filePath: String, live: Boolean): Unit = {
    val server = ManagementFactory.getPlatformMBeanServer
    val mxBean = ManagementFactory.newPlatformMXBeanProxy(
      server,
      "com.sun.management:type=HotSpotDiagnostic",
      classOf[HotSpotDiagnosticMXBean]
    )
    mxBean.dumpHeap(filePath, live)
  }

  def gcClassHistogram: String = {
    gcRun()
    gcRun()
    gcRun()
    gcRun()
    gcRun()
    gcRun()
    invoke("gcClassHistogram")
  }

  //  commands examples:
  //  gcClassHistogram - 2
  //  gcClassStats - 2
  //  gcRotateLog - 2
  //  gcRun - 2
  //  gcRunFinalization - 2
  //  help - 2
  //  jfrCheck - 2
  //  jfrDump - 2
  //  jfrStart - 2
  //  jfrStop - 2
  //  threadPrint - 2
  //  vmCheckCommercialFeatures - 2
  //  vmCommandLine - 2
  //  vmFlags - 2
  //  vmNativeMemory - 2
  //  vmSystemProperties - 2
  //  vmUnlockCommercialFeatures - 2
  //  vmUptime - 2
  //  vmVersion - 2
  private def invoke(operationName: String): String = {
    try {
      server
        .invoke(objectName, operationName, Array[AnyRef](null), Array[String](classOf[Array[String]].getName))
        .asInstanceOf[String]
    } catch {
      case exception @ (_: InstanceNotFoundException | _: ReflectionException | _: MBeanException) =>
        "ERROR: Unable to access '" + operationName + "' - " + exception
    }
  }
}
