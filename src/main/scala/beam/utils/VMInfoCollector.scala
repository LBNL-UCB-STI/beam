package beam.utils

import java.lang.management.ManagementFactory

import javax.management.{
  InstanceNotFoundException,
  MBeanException,
  MBeanServer,
  MalformedObjectNameException,
  ObjectName,
  ReflectionException
}

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

  def gcClassHistogram: String = {
    gcRun()
    invoke("gcClassHistogram")
  }

  //  commands examples:
  //  - gcClassHistogram
  //  - gcClassStats
  //  - gcRun
  //  - threadPrint
  //  - vmUptime
  //  - vmFlags
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
